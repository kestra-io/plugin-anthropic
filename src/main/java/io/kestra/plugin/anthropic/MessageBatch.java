package io.kestra.plugin.anthropic;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.batches.BatchCreateParams;
import com.anthropic.models.messages.batches.MessageBatchCanceledResult;
import com.anthropic.models.messages.batches.MessageBatchErroredResult;
import com.anthropic.models.messages.batches.MessageBatchExpiredResult;
import com.anthropic.models.messages.batches.MessageBatchIndividualResponse;
import com.anthropic.models.messages.batches.MessageBatchResult;
import com.anthropic.models.messages.batches.MessageBatchSucceededResult;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.type.TypeReference;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Metric;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.executions.metrics.Counter;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode(callSuper = true)
@Getter
@NoArgsConstructor
@Schema(
    title = "Create, retrieve, or cancel an Anthropic Message Batch",
    description = """
        Submits a batch of Messages API requests, retrieves one that already exists, or cancels it.
        Retrieving returns status and request counts; once status is `ended`, it also returns the results URL and each request output.
        Repeat RETRIEVE from a LoopUntil task until `status` is `ended`.
        Batches run asynchronously — most finish within an hour and all end within 24 hours — and are billed at half the real-time Messages API rate.
        Refer to the [Anthropic Console](https://console.anthropic.com/settings/keys) for an API key and the [Message Batches guide](https://platform.claude.com/docs/en/build-with-claude/batch-processing) for API behavior."""
)
@Plugin(
    examples = {
        @Example(
            title = "Submit a message batch and retrieve it until processing ends.",
            full = true,
            code = """
                id: anthropic_message_batch
                namespace: company.team

                tasks:
                  - id: create_batch
                    type: io.kestra.plugin.anthropic.MessageBatch
                    apiKey: "{{ secret('ANTHROPIC_API_KEY') }}"
                    mode: CREATE
                    requests:
                      - customId: capital-japan
                        model: claude-sonnet-4-6
                        maxTokens: 64
                        messages:
                          - type: USER
                            content: "What is the capital of Japan? Answer with one word and no punctuation."
                      - customId: capital-france
                        model: claude-sonnet-4-6
                        maxTokens: 64
                        messages:
                          - type: USER
                            content: "What is the capital of France? Answer with one word and no punctuation."

                  - id: wait_until_ended
                    type: io.kestra.plugin.core.flow.LoopUntil
                    condition: "{{ outputs.retrieve.status == 'ended' }}"
                    checkFrequency:
                      interval: PT30S
                      maxDuration: PT24H
                    tasks:
                      - id: retrieve
                        type: io.kestra.plugin.anthropic.MessageBatch
                        apiKey: "{{ secret('ANTHROPIC_API_KEY') }}"
                        mode: RETRIEVE
                        batchId: "{{ outputs.create_batch.batchId }}"
                """
        ),
        @Example(
            title = "Cancel an in-progress message batch.",
            full = true,
            code = """
                id: anthropic_cancel_message_batch
                namespace: company.team

                tasks:
                  - id: cancel_batch
                    type: io.kestra.plugin.anthropic.MessageBatch
                    apiKey: "{{ secret('ANTHROPIC_API_KEY') }}"
                    mode: CANCEL
                    batchId: "{{ outputs.create_batch.batchId }}"
                """
        )
    },
    metrics = {
        @Metric(
            name = "usage.input.tokens",
            type = Counter.TYPE,
            unit = "token",
            description = "Input tokens consumed by succeeded requests when mode is RETRIEVE."
        ),
        @Metric(
            name = "usage.output.tokens",
            type = Counter.TYPE,
            unit = "token",
            description = "Output tokens generated by succeeded requests when mode is RETRIEVE."
        ),
        @Metric(
            name = "usage.cache.creation.tokens",
            type = Counter.TYPE,
            unit = "token",
            description = "Tokens written to the prompt cache by succeeded requests when mode is RETRIEVE."
        ),
        @Metric(
            name = "usage.cache.read.tokens",
            type = Counter.TYPE,
            unit = "token",
            description = "Tokens read from the prompt cache by succeeded requests when mode is RETRIEVE."
        )
    }
)
public class MessageBatch extends AbstractAnthropic implements RunnableTask<MessageBatch.Output> {
    private static final String ENDED = "ended";

    @Schema(
        title = "Mode",
        description = """
            CREATE submits `requests` and returns a batch id.
            RETRIEVE reads status and request counts for a `batchId`. When status is `ended`, it also returns each request result.
            CANCEL asks Anthropic to stop a batch that has not ended yet."""
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<Mode> mode;

    @Schema(
        title = "Requests",
        description = "Required when mode is CREATE. Each item is one Messages API call. `customId` must be unique within the batch."
    )
    @PluginProperty(group = "main")
    private Property<List<BatchRequest>> requests;

    @Schema(
        title = "Batch ID",
        description = "Required when mode is RETRIEVE or CANCEL. Use the `batchId` output of CREATE."
    )
    @PluginProperty(group = "main")
    private Property<String> batchId;

    @JsonIgnore
    @Getter(AccessLevel.NONE)
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @Schema(hidden = true)
    @Builder.Default
    private final transient AtomicReference<StreamResponse<MessageBatchIndividualResponse>> openResults = new AtomicReference<>();

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rApiKey = runContext.render(apiKey).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("apiKey is required"));
        var rMode = runContext.render(mode).as(Mode.class)
            .orElseThrow(() -> new IllegalArgumentException("mode is required"));

        var client = buildClient(rApiKey);
        try {
            return switch (rMode) {
                case CREATE -> createBatch(runContext, client);
                case RETRIEVE -> retrieveBatch(runContext, client);
                case CANCEL -> cancelBatch(runContext, client);
            };
        } finally {
            closeResults();
            client.close();
        }
    }

    @Override
    public void kill() {
        closeResults();
    }

    @Override
    public void stop() {
        closeResults();
    }

    private Output createBatch(RunContext runContext, AnthropicClient client) throws Exception {
        var rRequests = runContext.render(requests).asList(BatchRequest.class);
        if (rRequests.isEmpty()) {
            throw new IllegalArgumentException("requests must contain at least one item when mode is CREATE");
        }

        var seen = new HashSet<String>();
        var params = BatchCreateParams.builder();
        for (var request : rRequests) {
            runContext.validate(request);
            var customId = request.customId().strip();
            if (!seen.add(customId)) {
                throw new IllegalArgumentException(
                    "Duplicate customId '" + customId + "'. Each request in a batch must have a unique customId."
                );
            }
            params.addRequest(toSdkRequest(request, customId));
        }

        var batch = client.messages().batches().create(params.build());
        runContext.logger().info(
            "Created Anthropic message batch {} with {} requests.",
            batch.id(),
            rRequests.size()
        );
        return toOutput(batch, null);
    }

    private Output retrieveBatch(RunContext runContext, AnthropicClient client) throws Exception {
        var rBatchId = renderBatchId(runContext);
        var batch = client.messages().batches().retrieve(rBatchId);
        var status = batch.processingStatus().asString();
        runContext.logger().info("Anthropic message batch {} is {}.", batch.id(), status);

        var results = ENDED.equals(status) ? readResults(runContext, client, batch.id()) : null;
        return toOutput(batch, results);
    }

    private Output cancelBatch(RunContext runContext, AnthropicClient client) throws Exception {
        var rBatchId = renderBatchId(runContext);
        var batch = client.messages().batches().cancel(rBatchId);
        runContext.logger().info(
            "Cancellation initiated for Anthropic message batch {}; status is {}.",
            batch.id(),
            batch.processingStatus().asString()
        );
        return toOutput(batch, null);
    }

    private String renderBatchId(RunContext runContext) throws Exception {
        return runContext.render(batchId).as(String.class)
            .map(String::strip)
            .filter(id -> !id.isBlank())
            .orElseThrow(() -> new IllegalArgumentException("batchId is required when mode is RETRIEVE or CANCEL"));
    }

    private List<RequestResult> readResults(RunContext runContext, AnthropicClient client, String messageBatchId) {
        var stream = client.messages().batches().resultsStreaming(messageBatchId);
        openResults.set(stream);
        try {
            return stream.stream()
                .map(item -> toRequestResult(runContext, item))
                .toList();
        } finally {
            closeResults();
        }
    }

    private void closeResults() {
        Optional.ofNullable(openResults)
            .map(ref -> ref.getAndSet(null))
            .ifPresent(StreamResponse::close);
    }

    private RequestResult toRequestResult(RunContext runContext, MessageBatchIndividualResponse item) {
        var customId = item.customId();
        return item.result().accept(new MessageBatchResult.Visitor<>() {
            @Override
            public RequestResult visitSucceeded(MessageBatchSucceededResult succeeded) {
                return fromMessage(runContext, customId, succeeded.message());
            }

            @Override
            public RequestResult visitErrored(MessageBatchErroredResult errored) {
                return RequestResult.builder()
                    .customId(customId)
                    .type("errored")
                    .errorMessage(errored.error().error().message())
                    .rawResponse(writeJson(errored.error()))
                    .build();
            }

            @Override
            public RequestResult visitCanceled(MessageBatchCanceledResult canceled) {
                return RequestResult.builder()
                    .customId(customId)
                    .type("canceled")
                    .build();
            }

            @Override
            public RequestResult visitExpired(MessageBatchExpiredResult expired) {
                return RequestResult.builder()
                    .customId(customId)
                    .type("expired")
                    .build();
            }

            @Override
            public RequestResult unknown(JsonValue json) {
                return RequestResult.builder()
                    .customId(customId)
                    .type("unknown")
                    .rawResponse(json == null ? null : json.toString())
                    .build();
            }
        });
    }

    private RequestResult fromMessage(RunContext runContext, String customId, Message message) {
        sendMetrics(runContext, message);

        var outputText = new StringBuilder();
        List<ChatCompletion.ToolUse> toolUses = new ArrayList<>();
        for (ContentBlock block : message.content()) {
            block.text().ifPresent(text -> outputText.append(text.text()));
            block.toolUse().ifPresent(
                toolUse -> toolUses.add(
                    ChatCompletion.ToolUse.builder()
                        .id(toolUse.id())
                        .name(toolUse.name())
                        .input(readToolInput(toolUse._input()))
                        .build()
                )
            );
        }

        return RequestResult.builder()
            .customId(customId)
            .type("succeeded")
            .rawResponse(writeJson(message))
            .outputText(outputText.toString())
            .toolUses(toolUses.isEmpty() ? null : List.copyOf(toolUses))
            .stopReason(message.stopReason().map(StopReason::asString).orElse(null))
            .cacheCreationInputTokens(message.usage().cacheCreationInputTokens().orElse(null))
            .cacheReadInputTokens(message.usage().cacheReadInputTokens().orElse(null))
            .build();
    }

    private static BatchCreateParams.Request toSdkRequest(BatchRequest request, String customId) {
        var params = BatchCreateParams.Request.Params.builder()
            .model(request.model())
            .maxTokens(Optional.ofNullable(request.maxTokens()).orElse(1024L))
            .messages(toMessages(request));

        Optional.ofNullable(request.system()).map(String::strip).filter(text -> !text.isBlank()).ifPresent(params::system);
        Optional.ofNullable(request.temperature()).ifPresent(params::temperature);
        Optional.ofNullable(request.topP()).ifPresent(params::topP);
        Optional.ofNullable(request.topK()).map(Integer::longValue).ifPresent(params::topK);
        Optional.ofNullable(request.promptCaching())
            .filter(Boolean.TRUE::equals)
            .ifPresent(ignored -> params.cacheControl(CacheControlEphemeral.builder().build()));

        return BatchCreateParams.Request.builder()
            .customId(customId)
            .params(params.build())
            .build();
    }

    private static List<MessageParam> toMessages(BatchRequest request) {
        return request.messages().stream()
            .map(
                message -> MessageParam.builder()
                    .role(MessageParam.Role.of(message.type().role()))
                    .content(message.content())
                    .build()
            )
            .toList();
    }

    private static Output toOutput(com.anthropic.models.messages.batches.MessageBatch batch, List<RequestResult> results) {
        var counts = batch.requestCounts();
        return Output.builder()
            .batchId(batch.id())
            .status(batch.processingStatus().asString())
            .resultsUrl(batch.resultsUrl().orElse(null))
            .requestCounts(
                RequestCounts.builder()
                    .processing(counts.processing())
                    .succeeded(counts.succeeded())
                    .errored(counts.errored())
                    .canceled(counts.canceled())
                    .expired(counts.expired())
                    .build()
            )
            .results(results)
            .build();
    }

    private static String writeJson(Object value) {
        try {
            return JacksonMapper.ofJson().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize the Anthropic response", e);
        }
    }

    private static Map<String, Object> readToolInput(JsonValue input) {
        try {
            var json = JacksonMapper.ofJson().writeValueAsString(input);
            return JacksonMapper.ofJson().readValue(json, new TypeReference<>() {
            });
        } catch (Exception ignored) {
            return null;
        }
    }

    public enum Mode {
        CREATE,
        RETRIEVE,
        CANCEL
    }

    @Builder
    public record BatchRequest(
        @NotBlank
        @Schema(
            title = "Custom ID",
            description = "Developer-provided id, unique within the batch. Results are not ordered, so match them on this id."
        ) String customId,

        @NotBlank
        @Schema(
            title = "Model",
            description = "Claude model for this request (for example, claude-sonnet-4-6)."
        ) String model,

        @NotEmpty
        @Valid
        @Schema(
            title = "Messages",
            description = "Ordered chat turns for this request. Include at least one USER message."
        ) List<ChatCompletion.ChatMessage> messages,

        @Min(1)
        @Schema(
            title = "Max tokens",
            description = "Maximum tokens Anthropic can generate for this request. Defaults to 1024. Must be at least 1."
        ) Long maxTokens,

        @Schema(
            title = "System prompt",
            description = "Optional system instructions for this request."
        ) String system,

        @DecimalMin("0")
        @DecimalMax("1")
        @Schema(
            title = "Temperature",
            description = "Sampling randomness from 0.0 to 1.0. Unset by default and only sent when provided."
        ) Double temperature,

        @DecimalMin("0")
        @DecimalMax("1")
        @Schema(title = "Top P", description = "Nucleus sampling cap from 0.0 to 1.0. Unset by default.") Double topP,

        @Min(1)
        @Schema(title = "Top K", description = "Sample only from the top K tokens. Unset by default. Must be at least 1 when set.") Integer topK,

        @Schema(
            title = "Prompt caching",
            description = "Cache the longest cacheable prefix of this request. Cached tokens are billed at a reduced rate."
        ) Boolean promptCaching) {
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Batch ID", description = "Anthropic id of the Message Batch.")
        private String batchId;

        @Schema(
            title = "Status",
            description = "Processing status: `in_progress`, `canceling`, or `ended`."
        )
        private String status;

        @Schema(
            title = "Request counts",
            description = "Tallies by status. Counts other than processing stay at zero until the whole batch ends."
        )
        private RequestCounts requestCounts;

        @Schema(
            title = "Results URL",
            description = "URL of the JSONL results file. Present once processing has ended."
        )
        private String resultsUrl;

        @Schema(
            title = "Results",
            description = "Per-request outputs. Present only when mode is RETRIEVE and status is `ended`. Match rows on `customId`; order is not guaranteed."
        )
        private List<RequestResult> results;
    }

    @Builder
    @Getter
    public static class RequestCounts {
        @Schema(title = "Processing", description = "Requests still processing. This is the only non-zero tally until the batch ends.")
        private Long processing;

        @Schema(title = "Succeeded", description = "Requests that completed successfully.")
        private Long succeeded;

        @Schema(title = "Errored", description = "Requests that failed.")
        private Long errored;

        @Schema(title = "Canceled", description = "Requests that were canceled.")
        private Long canceled;

        @Schema(title = "Expired", description = "Requests that expired before processing finished.")
        private Long expired;
    }

    @Builder
    @Getter
    public static class RequestResult {
        @Schema(title = "Custom ID", description = "The `customId` of the request this result belongs to.")
        private String customId;

        @Schema(
            title = "Result type",
            description = "`succeeded`, `errored`, `canceled`, `expired`, or `unknown`."
        )
        private String type;

        @Schema(title = "Raw response", description = "JSON for the succeeded message, or for the error payload when the request errored.")
        private String rawResponse;

        @Schema(title = "Output text", description = "Assistant text extracted from a succeeded message.")
        private String outputText;

        @Schema(title = "Tool uses", description = "Tools the model asked to call, when the succeeded message contains tool-use blocks.")
        private List<ChatCompletion.ToolUse> toolUses;

        @Schema(title = "Stop reason", description = "Why the model stopped generating (for example, end_turn or max_tokens).")
        private String stopReason;

        @Schema(title = "Cache creation input tokens", description = "Tokens written to the prompt cache for this request.")
        private Long cacheCreationInputTokens;

        @Schema(title = "Cache read input tokens", description = "Tokens read from the prompt cache for this request.")
        private Long cacheReadInputTokens;

        @Schema(title = "Error message", description = "Anthropic error message when the result type is `errored`.")
        private String errorMessage;
    }
}
