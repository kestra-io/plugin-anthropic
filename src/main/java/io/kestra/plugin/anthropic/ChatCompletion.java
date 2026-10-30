package io.kestra.plugin.anthropic;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.anthropic.client.AnthropicClient;
import com.anthropic.helpers.MessageAccumulator;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.Base64PdfSource;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.CitationsConfigParam;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.DocumentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingConfigEnabled;
import com.anthropic.models.messages.ToolChoiceAny;
import com.anthropic.models.messages.ToolChoiceAuto;
import com.anthropic.models.messages.ToolChoiceNone;
import com.anthropic.models.messages.ToolChoiceTool;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

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
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Send chat messages with Claude",
    description = "Calls the Anthropic Messages API with rendered inputs, optional system prompt, and sampling controls; defaults to maxTokens 1024 while emitting token usage counters. Refer to the [Anthropic Console Settings](https://console.anthropic.com/settings/keys) to create an API key and the [Anthropic API documentation](https://docs.anthropic.com/claude/reference/messages_post) for more information."
)
@Plugin(
    examples = {
        @Example(
            title = "Chat completion using Claude.",
            full = true,
            code = """
                id: anthropic_chat_completion
                namespace: company.team

                tasks:
                  - id: chat_completion
                    type: io.kestra.plugin.anthropic.ChatCompletion
                    apiKey: "{{ secret('ANTHROPIC_API_KEY') }}"
                    model: "claude-sonnet-4-6"
                    maxTokens: 1024
                    messages:
                      - type: USER
                        content: "What is the capital of Japan? Answer with a unique word and without any punctuation."
                """
        ),
        @Example(
            title = "Code generation using Claude",
            full = true,
            code = """
                id: anthropic_code_generation
                namespace: company.team

                tasks:
                  - id: code_generation
                    type: io.kestra.plugin.anthropic.ChatCompletion
                    apiKey: "{{ secret('ANTHROPIC_API_KEY') }}"
                    model: "claude-sonnet-4-6"
                    maxTokens: 1500
                    temperature: 0.3
                    messages:
                      - type: USER
                        content: |
                          Write a Python function that:
                          1. Takes a list of numbers as input
                          2. Filters out negative numbers
                          3. Calculates the average of remaining positive numbers
                          4. Returns the result rounded to 2 decimal places
                          5. Include error handling for empty lists
                          Also provide 3 test cases with expected outputs.
                """
        ),
        @Example(
            title = "Conversation with follow-up context",
            full = true,
            code = """
                id: anthropic_context_conversation
                namespace: company.team

                tasks:
                  - id: code_generation
                    type: io.kestra.plugin.anthropic.ChatCompletion
                    apiKey: "{{ secret('ANTHROPIC_API_KEY') }}"
                    model: "claude-sonnet-4-6"
                    maxTokens: 800
                    temperature: 0.5
                    messages:
                      - type: USER
                        content: "Explain quantum computing in simple terms."
                      - type: ASSISTANT
                        content: "Quantum computing uses quantum mechanical phenomena like superposition and entanglement to process information differently than classical computers. Instead of bits that are either 0 or 1, quantum computers use quantum bits (qubits) that can exist in multiple states simultaneously."
                      - type: USER
                        content: "That is helpful! Can you give me a practical example of how this could be used in everyday life in the next 10 years?"
                """
        ),
        @Example(
            title = "Chat completion with prompt caching",
            full = true,
            code = """
                id: anthropic_cached_chat
                namespace: company.team

                tasks:
                  - id: cached_chat
                    type: io.kestra.plugin.anthropic.ChatCompletion
                    apiKey: "{{ secret('ANTHROPIC_API_KEY') }}"
                    model: "claude-sonnet-4-6"
                    maxTokens: 1024
                    promptCaching: true
                    system: "You are a helpful assistant with extensive knowledge about Kestra, the open-source orchestration platform."
                    messages:
                      - type: USER
                        content: "What are the key features of Kestra?"
                """
        ),
        @Example(
            title = "Tool-assisted structured extraction",
            full = true,
            code = """
                id: anthropic_structured_output
                namespace: company.team

                tasks:
                  - id: extract_data
                    type: io.kestra.plugin.anthropic.ChatCompletion
                    apiKey: "{{ secret('ANTHROPIC_API_KEY') }}"
                    model: "claude-sonnet-4-6"
                    maxTokens: 1024
                    messages:
                      - type: USER
                        content: |
                          Extract the following information from this text:
                          "John Doe is 30 years old and works as a Software Engineer in San Francisco."
                    tools:
                      - name: extract_person_info
                        description: "Extract structured information about a person"
                        inputSchema:
                          type: object
                          properties:
                            name:
                              type: string
                              description: "The person's full name"
                            age:
                              type: integer
                              description: "The person's age"
                            occupation:
                              type: string
                              description: "The person's job title"
                            location:
                              type: string
                              description: "The person's location"
                          required:
                            - name
                            - age
                """
        ),
        @Example(
            title = "Chat completion with Anthropic web search",
            full = true,
            code = """
                id: anthropic_web_search
                namespace: company.team

                tasks:
                  - id: search
                    type: io.kestra.plugin.anthropic.ChatCompletion
                    apiKey: "{{ secret('ANTHROPIC_API_KEY') }}"
                    model: "claude-sonnet-4-6"
                    maxTokens: 2048
                    messages:
                      - type: USER
                        content: "Summarize recent news about open-source orchestration tools."
                    tools:
                      - type: WEB_SEARCH
                        maxUses: 3
                """
        ),
        @Example(
            title = "Stream a response with extended thinking and cost attribution",
            full = true,
            code = """
                id: anthropic_thinking_stream
                namespace: company.team

                tasks:
                  - id: reason
                    type: io.kestra.plugin.anthropic.ChatCompletion
                    apiKey: "{{ secret('ANTHROPIC_API_KEY') }}"
                    model: claude-sonnet-4-6
                    maxTokens: 4096
                    thinking: true
                    budgetTokens: 1024
                    stream: true
                    stopSequences: ["END"]
                    metadata:
                      userId: workflow-analysis
                    serviceTier: STANDARD_ONLY
                    connectTimeout: PT30S
                    readTimeout: PT5M
                    maxRetries: 2
                    messages:
                      - type: USER
                        content: "Explain why the sum of two odd integers is even."
                """
        ),
        @Example(
            title = "Analyze images and PDFs with citations and explicit cache breakpoints",
            full = true,
            code = """
                id: anthropic_multimodal
                namespace: company.team

                tasks:
                  - id: analyze
                    type: io.kestra.plugin.anthropic.ChatCompletion
                    apiKey: "{{ secret('ANTHROPIC_API_KEY') }}"
                    model: claude-sonnet-4-6
                    maxTokens: 2048
                    systemPromptBlocks:
                      - text: "Analyze the supplied sources and cite evidence."
                        cacheControl: ephemeral
                    messages:
                      - type: USER
                        cacheControl: ephemeral
                        content:
                          - type: text
                            text: "Compare the image with the report."
                          - type: image
                            source:
                              type: url
                              url: https://upload.wikimedia.org/wikipedia/commons/a/a9/Example.jpg
                          - type: document
                            source:
                              type: url
                              url: https://www.w3.org/WAI/ER/tests/xhtml/testfiles/resources/pdf/dummy.pdf
                            title: Report
                            citations:
                              enabled: true
                """
        ),
        @Example(
            title = "Use uploaded files and base64 content with beta API features",
            full = true,
            code = """
                id: anthropic_file_content
                namespace: company.team

                inputs:
                  - id: image_base64
                    type: STRING
                  - id: pdf_base64
                    type: STRING
                  - id: image_file_id
                    type: STRING
                  - id: pdf_file_id
                    type: STRING

                tasks:
                  - id: analyze
                    type: io.kestra.plugin.anthropic.ChatCompletion
                    apiKey: "{{ secret('ANTHROPIC_API_KEY') }}"
                    model: claude-sonnet-4-6
                    betas: [files-api-2025-04-14]
                    messages:
                      - type: USER
                        content:
                          - type: text
                            text: "Summarize these sources. File IDs come from UploadFile."
                          - type: image
                            source:
                              type: base64
                              mediaType: image/png
                              data: "{{ inputs.image_base64 }}"
                          - type: document
                            source:
                              type: base64
                              mediaType: application/pdf
                              data: "{{ inputs.pdf_base64 }}"
                          - type: image
                            source:
                              type: file
                              fileId: "{{ inputs.image_file_id }}"
                          - type: document
                            source:
                              type: file
                              fileId: "{{ inputs.pdf_file_id }}"
                """
        ),
        @Example(
            title = "Select a tool and cache its definition through a custom endpoint",
            full = true,
            code = """
                id: anthropic_tool_choice
                namespace: company.team

                tasks:
                  - id: extract
                    type: io.kestra.plugin.anthropic.ChatCompletion
                    apiKey: "{{ secret('ANTHROPIC_API_KEY') }}"
                    baseUrl: https://api.anthropic.com
                    model: claude-sonnet-4-6
                    toolChoice: TOOL
                    toolChoiceName: extract_name
                    tools:
                      - name: extract_name
                        description: Extract a person's name
                        cacheControl: ephemeral
                        inputSchema:
                          type: object
                          properties:
                            name:
                              type: string
                          required: [name]
                    messages:
                      - type: USER
                        content: "My name is Ada."
                """
        ),
    },
    metrics = {
        @Metric(
            name = "usage.input.tokens",
            type = Counter.TYPE,
            unit = "token",
            description = "Number of input tokens processed by the model."
        ),
        @Metric(
            name = "usage.output.tokens",
            type = Counter.TYPE,
            unit = "token",
            description = "Number of output tokens generated by the model."
        ),
        @Metric(
            name = "usage.cache.creation.tokens",
            type = Counter.TYPE,
            unit = "token",
            description = "Number of tokens written to the prompt cache."
        ),
        @Metric(
            name = "usage.cache.read.tokens",
            type = Counter.TYPE,
            unit = "token",
            description = "Number of tokens read from the prompt cache."
        )
    }
)
public class ChatCompletion extends AbstractAnthropicChat implements RunnableTask<ChatCompletion.Output> {

    @Schema(
        title = "Messages",
        description = "Ordered chat turns; include at least one USER message. Content accepts a string or a list of text, image, and document blocks. Image/PDF sources support url, base64, and file (fileId); file sources require the Files API beta in betas."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<List<ChatMessage>> messages;

    @Schema(title = "System prompt", description = "Optional system instructions applied to the whole conversation. Use systemPromptBlocks instead for per-block cache control.")
    @PluginProperty(group = "advanced")
    private Property<String> system;

    @Schema(title = "System prompt blocks", description = "Optional system blocks with text and cacheControl: ephemeral. Mutually exclusive with system.")
    @PluginProperty(group = "advanced")
    private Property<List<SystemPromptBlock>> systemPromptBlocks;

    @Schema(
        title = "Extended thinking",
        description = "Enable Claude's extended thinking. Requires budgetTokens and sets temperature to 1, warning when a different temperature is provided. Defaults to false."
    )
    @PluginProperty(group = "advanced")
    @Builder.Default
    private Property<Boolean> thinking = Property.ofValue(false);

    @Schema(title = "Thinking token budget", description = "Required when thinking is enabled. At least 1024 and strictly less than maxTokens.")
    @PluginProperty(group = "advanced")
    private Property<@Min(1024) Integer> budgetTokens;

    @Schema(
        title = "Tool choice", description = "AUTO lets Claude decide; ANY requires a tool; TOOL requires toolChoiceName; NONE disables tools. Forced tool use is incompatible with thinking."
    )
    @PluginProperty(group = "advanced")
    private Property<ToolChoice> toolChoice;

    @Schema(title = "Tool choice name", description = "Name of the tool to call when toolChoice is TOOL.")
    @PluginProperty(group = "advanced")
    private Property<String> toolChoiceName;

    @Schema(title = "Stop sequences", description = "Optional strings that stop generation. The matched string is returned in stopSequence.")
    @PluginProperty(group = "advanced")
    private Property<List<String>> stopSequences;

    @Schema(
        title = "Stream response",
        description = "Log complete text lines as they arrive, flush the final partial line, and return the complete response and usage at the end. Defaults to false."
    )
    @PluginProperty(group = "execution")
    @Builder.Default
    private Property<Boolean> stream = Property.ofValue(false);

    @Schema(title = "Metadata", description = "Optional attribution metadata containing userId. Do not include personal information.")
    @PluginProperty(group = "advanced")
    private Property<Metadata> metadata;

    @Schema(title = "Service tier", description = "AUTO (Anthropic chooses the tier) or STANDARD_ONLY (standard capacity only). Unset uses the API default.")
    @PluginProperty(group = "advanced")
    private Property<ServiceTier> serviceTier;

    @Schema(title = "Beta features", description = "Optional Anthropic beta identifiers sent in the anthropic-beta header, for example files-api-2025-04-14 for file_id sources.")
    @PluginProperty(group = "advanced")
    private Property<List<String>> betas;

    @Schema(
        title = "Tools",
        description = "Tools Claude can use. Each entry is either a user-defined tool (`name`, optional `description`, `inputSchema` JSON Schema) or an Anthropic built-in tool selected with `type` (currently `WEB_SEARCH`, with optional `maxUses`, `allowedDomains` and `blockedDomains`). With built-in tools, Anthropic can end a long-running turn with `stopReason` `pause_turn`; the task does not resume it, so `outputText` may then be partial."
    )
    @PluginProperty(group = "destination")
    private Property<List<ChatTool>> tools;

    @Schema(
        title = "Prompt caching",
        description = """
            Enable prompt caching to reduce costs and latency for repetitive prompts.
            When enabled, the API automatically caches the longest cacheable prefix of the request.
            Cached tokens are billed at a reduced rate.
            See the [Anthropic prompt caching documentation](https://docs.anthropic.com/en/docs/build-with-claude/prompt-caching) for details."""
    )
    @Builder.Default
    @PluginProperty(group = "advanced")
    private Property<Boolean> promptCaching = Property.ofValue(false);

    @JsonIgnore
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private transient volatile AnthropicClient activeClient;

    @JsonIgnore
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private transient volatile Future<Message> activeRequest;

    @JsonIgnore
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private transient volatile boolean killed;

    @Override
    public Output run(RunContext runContext) throws Exception {
        var params = createParams(runContext);
        var rApiKey = runContext.render(apiKey).as(String.class).filter(value -> !value.isBlank())
            .orElseThrow(() -> new IllegalArgumentException("apiKey is required"));
        var rStream = runContext.render(stream).as(Boolean.class).orElse(false);
        var client = buildClient(runContext, rApiKey);
        this.activeClient = client;
        try {
            if (killed) {
                throw new InterruptedException("Chat completion was killed");
            }
            Message response;
            if (rStream) {
                response = streamResponse(runContext, client, params);
            } else {
                response = client.messages().create(params);
            }
            if (killed) {
                throw new InterruptedException("Chat completion was killed");
            }
            sendMetrics(runContext, response);
            return toOutput(runContext, response);
        } finally {
            this.activeClient = null;
            client.close();
        }
    }

    @Override
    public void kill() {
        killed = true;
        var request = this.activeRequest;
        var client = this.activeClient;
        try {
            if (request != null) {
                request.cancel(true);
            }
        } finally {
            if (client != null) {
                client.close();
            }
        }
    }

    private Message streamResponse(RunContext runContext, AnthropicClient client, MessageCreateParams params) throws Exception {
        // Closing the SDK reader can block on its read lock; interrupt a virtual thread to unblock the socket first.
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var request = executor.submit(() -> consumeStream(runContext, client, params));
            this.activeRequest = request;
            try {
                if (killed) {
                    request.cancel(true);
                }
                return request.get();
            } catch (ExecutionException e) {
                if (e.getCause() instanceof Exception cause) {
                    throw cause;
                }
                throw new IllegalStateException("Unable to consume Anthropic response stream", e.getCause());
            } finally {
                request.cancel(true);
                this.activeRequest = null;
            }
        }
    }

    private static Message consumeStream(RunContext runContext, AnthropicClient client, MessageCreateParams params) {
        var accumulator = MessageAccumulator.create();
        var pendingText = new StringBuilder();
        try (var events = client.messages().createStreaming(params)) {
            events.stream().forEach(event ->
            {
                accumulator.accumulate(event);
                event.contentBlockDelta().ifPresent(delta -> delta.delta().text().ifPresent(text -> logStreamText(runContext, pendingText, text.text())));
            });
            return accumulator.message();
        } finally {
            if (!pendingText.isEmpty()) {
                runContext.logger().info("{}", pendingText.toString());
            }
        }
    }

    private static void logStreamText(RunContext runContext, StringBuilder pendingText, String text) {
        pendingText.append(text);
        int newline;
        while ((newline = pendingText.indexOf("\n")) >= 0) {
            var end = newline > 0 && pendingText.charAt(newline - 1) == '\r' ? newline - 1 : newline;
            runContext.logger().info("{}", pendingText.substring(0, end));
            pendingText.delete(0, newline + 1);
        }
    }

    MessageCreateParams createParams(RunContext runContext) throws Exception {
        var rModel = runContext.render(model).as(String.class).filter(value -> !value.isBlank())
            .orElseThrow(() -> new IllegalArgumentException("model is required"));
        var rMaxTokens = runContext.render(maxTokens).as(Long.class).orElse(1024L);
        var rMessages = runContext.render(messages).asList(ChatMessage.class);
        var rTemperature = runContext.render(temperature).as(Double.class);
        var rTopP = runContext.render(topP).as(Double.class);
        var rTopK = runContext.render(topK).as(Integer.class);
        var rSystem = runContext.render(system).as(String.class);
        var rSystemPromptBlocks = runContext.render(systemPromptBlocks).asList(SystemPromptBlock.class);
        var rAllTools = runContext.render(tools).asList(ChatTool.class);
        var rTools = rAllTools.stream().filter(Tool.class::isInstance).map(Tool.class::cast).toList();
        var rBuiltInTools = rAllTools.stream().filter(BuiltInTool.class::isInstance).map(BuiltInTool.class::cast).toList();
        var rPromptCaching = runContext.render(promptCaching).as(Boolean.class).orElse(false);

        if (rMessages.isEmpty()) {
            throw new IllegalArgumentException("messages must contain at least one item");
        }
        var messageParams = rMessages.stream().map(ChatMessage::toSdkMessage).toList();

        var paramsBuilder = MessageCreateParams.builder()

            .model(Model.of(rModel))
            .maxTokens(rMaxTokens);

        if (rSystem.isPresent() && !rSystemPromptBlocks.isEmpty()) {
            throw new IllegalArgumentException("Use either system or systemPromptBlocks, not both");
        }
        rSystem.ifPresent(paramsBuilder::system);
        if (!rSystemPromptBlocks.isEmpty()) {
            paramsBuilder.systemOfTextBlockParams(rSystemPromptBlocks.stream().map(SystemPromptBlock::toSdkBlock).toList());
        }
        rTemperature.ifPresent(paramsBuilder::temperature);
        rTopP.ifPresent(paramsBuilder::topP);
        rTopK.ifPresent(paramsBuilder::topK);

        var rThinking = runContext.render(thinking).as(Boolean.class).orElse(false);
        var rToolChoice = runContext.render(toolChoice).as(ToolChoice.class);
        if (rThinking) {
            var rBudgetTokens = runContext.render(budgetTokens).as(Integer.class)
                .orElseThrow(() -> new IllegalArgumentException("budgetTokens is required when thinking is enabled"));
            if (rBudgetTokens < 1024 || rBudgetTokens >= rMaxTokens) {
                throw new IllegalArgumentException("budgetTokens must be at least 1024 and less than maxTokens");
            }
            if (rToolChoice.filter(choice -> choice == ToolChoice.ANY || choice == ToolChoice.TOOL).isPresent()) {
                throw new IllegalArgumentException("thinking only supports toolChoice AUTO or NONE");
            }
            if (rTemperature.filter(value -> Double.compare(value, 1.0) != 0).isPresent()) {
                runContext.logger().warn("temperature is set to 1 because thinking is enabled; the configured temperature is ignored");
            }
            paramsBuilder.thinking(ThinkingConfigEnabled.builder().budgetTokens(rBudgetTokens.longValue()).build()).temperature(1.0);
        }
        if (rToolChoice.isPresent()) {
            switch (rToolChoice.get()) {
                case AUTO -> paramsBuilder.toolChoice(ToolChoiceAuto.builder().build());
                case ANY -> paramsBuilder.toolChoice(ToolChoiceAny.builder().build());
                case NONE -> paramsBuilder.toolChoice(ToolChoiceNone.builder().build());
                case TOOL -> paramsBuilder.toolChoice(
                    ToolChoiceTool.builder().name(
                        runContext.render(toolChoiceName).as(String.class).filter(name -> !name.isBlank())
                            .orElseThrow(() -> new IllegalArgumentException("toolChoiceName is required when toolChoice is TOOL"))
                    ).build()
                );
            }
        }
        var rStopSequences = runContext.render(stopSequences).asList(String.class);
        if (!rStopSequences.isEmpty()) {
            paramsBuilder.stopSequences(rStopSequences);
        }
        runContext.render(metadata).as(Metadata.class).ifPresent(value ->
        {
            if (value.userId() != null) {
                paramsBuilder.metadata(com.anthropic.models.messages.Metadata.builder().userId(value.userId()).build());
            }
        });
        runContext.render(serviceTier).as(ServiceTier.class).ifPresent(
            value -> paramsBuilder.serviceTier(
                MessageCreateParams.ServiceTier.of(value == ServiceTier.AUTO ? "auto" : "standard_only")
            )
        );
        var rBetas = runContext.render(betas).asList(String.class);
        if (!rBetas.isEmpty()) {
            paramsBuilder.putAdditionalHeader("anthropic-beta", String.join(",", rBetas));
        }

        // Add tools if provided
        if (!rTools.isEmpty()) {
            List<com.anthropic.models.messages.ToolUnion> toolParams = rTools.stream()
                .map(tool -> com.anthropic.models.messages.ToolUnion.ofTool(toSdkTool(tool)))
                .toList();
            paramsBuilder.tools(toolParams);
        }

        rBuiltInTools.forEach(tool -> paramsBuilder.addTool(tool.toSdkTool()));

        paramsBuilder.messages(messageParams);

        // Enable prompt caching if requested
        if (rPromptCaching) {
            paramsBuilder.cacheControl(CacheControlEphemeral.builder().build());
        }

        return paramsBuilder.build();
    }

    private static Output toOutput(RunContext runContext, Message response) throws Exception {
        var reasoning = new StringBuilder();
        var citations = new ArrayList<Citation>();
        for (var block : response.content()) {
            block.thinking().ifPresent(value -> reasoning.append(value.thinking()));
            if (block.text().isPresent()) {
                for (var citation : block.text().get().citations().orElse(List.of())) {
                    citations.add(JacksonMapper.ofJson().readValue(JacksonMapper.ofJson().writeValueAsString(citation), Citation.class));
                }
            }
        }
        return Output.builder()
            .rawResponse(JacksonMapper.ofJson().writeValueAsString(response))
            .outputText(outputText(response))
            .toolUses(toolUses(runContext, response))
            .stopReason(stopReason(response))
            .stopSequence(response.stopSequence().orElse(null))
            .thinking(reasoning.isEmpty() ? null : reasoning.toString())
            .citations(citations.isEmpty() ? null : List.copyOf(citations))
            .cacheCreationInputTokens(response.usage().cacheCreationInputTokens().orElse(null))
            .cacheReadInputTokens(response.usage().cacheReadInputTokens().orElse(null))
            .build();
    }

    @Builder
    public record ChatMessage(
        @Schema(title = "Message role") ChatMessageType type,
        @Schema(
            title = "Message content", description = "Plain text or a list of ContentBlock objects.", anyOf = {
                String.class, ContentBlock[].class }
        ) Object content,
        @Schema(title = "Cache control", description = "Optional ephemeral breakpoint applied to the final block of this message.") CacheControl cacheControl){
        public ChatMessage(ChatMessageType type, Object content) {
            this(type, content, null);
        }

        MessageParam toSdkMessage() {
            if (type == null || content == null) {
                throw new IllegalArgumentException("Each message must set type and content");
            }
            var builder = MessageParam.builder().role(MessageParam.Role.of(type.role()));
            if (content instanceof String text && cacheControl == null) {
                return builder.content(text).build();
            }
            var blocks = new ArrayList<ContentBlockParam>();
            if (content instanceof String text) {
                blocks.add(ContentBlockParam.ofText(TextBlockParam.builder().text(text).build()));
            } else if (content instanceof List<?> values && !values.isEmpty()) {
                values.forEach(value -> blocks.add(JacksonMapper.ofJson().convertValue(value, ContentBlock.class).toSdkBlock()));
            } else {
                throw new IllegalArgumentException("Message content must be a string or a non-empty list of content blocks");
            }
            if (cacheControl != null) {
                // A message-level breakpoint belongs on its final content block.
                var last = blocks.removeLast();
                var cache = ChatCompletion.cacheControl(cacheControl);
                if (last.isText()) {
                    blocks.add(ContentBlockParam.ofText(last.asText().toBuilder().cacheControl(cache).build()));
                } else if (last.isImage()) {
                    blocks.add(ContentBlockParam.ofImage(last.asImage().toBuilder().cacheControl(cache).build()));
                } else {
                    blocks.add(ContentBlockParam.ofDocument(last.asDocument().toBuilder().cacheControl(cache).build()));
                }
            }
            return builder.contentOfBlockParams(blocks).build();
        }
    }

    static CacheControlEphemeral cacheControl(CacheControl value) {
        return switch (value) {
            case EPHEMERAL -> CacheControlEphemeral.builder().build();
        };
    }

    @Builder
    public record SystemPromptBlock(
        @Schema(title = "System instructions") String text,
        @Schema(title = "Cache control", description = "Optional ephemeral cache breakpoint on this system block.") CacheControl cacheControl) {
        TextBlockParam toSdkBlock() {
            var builder = TextBlockParam.builder().text(required(text, "systemPromptBlocks[].text"));
            if (cacheControl != null) {
                builder.cacheControl(ChatCompletion.cacheControl(cacheControl));
            }
            return builder.build();
        }
    }

    @Builder
    public record ContentSource(
        @Schema(title = "Source type", description = "url, base64, or file.") ContentSourceType type,
        @Schema(title = "Source URL", description = "Public image or PDF URL when type is url.") String url,
        @Schema(title = "Base64 data", description = "Base64-encoded image or PDF bytes when type is base64.") String data,
        @JsonAlias("media_type") @Schema(title = "Media type", description = "Image MIME type or application/pdf for base64 sources.") String mediaType,
        @JsonAlias("file_id") @Schema(title = "File ID", description = "Anthropic file ID from UploadFile when type is file.") String fileId) {
    }

    @Builder
    public record ContentBlock(
        @Schema(title = "Block type", description = "text, image, or document.") ContentBlockType type,
        @Schema(title = "Text", description = "Text content when type is text.") String text,
        @Schema(title = "Source", description = "Image or PDF source.") ContentSource source,
        @Schema(title = "Document title") String title,
        @Schema(title = "Document context", description = "Optional context about the document.") String context,
        @Schema(title = "Citations", description = "Set enabled to true to request source citations for this document.") CitationsConfig citations,
        @Schema(title = "Cache control", description = "Optional ephemeral cache breakpoint on this block.") CacheControl cacheControl) {
        ContentBlockParam toSdkBlock() {
            if (type == null) {
                throw new IllegalArgumentException("Content block type is required: text, image, or document");
            }
            var cache = cacheControl == null ? null : ChatCompletion.cacheControl(cacheControl);
            if (type == ContentBlockType.TEXT) {
                if (text == null) {
                    throw new IllegalArgumentException("Text content blocks require text");
                }
                var builder = TextBlockParam.builder().text(text);
                if (cache != null) {
                    builder.cacheControl(cache);
                }
                return ContentBlockParam.ofText(builder.build());
            }
            if (source == null || source.type() == null) {
                throw new IllegalArgumentException("Image and document blocks require a source with type url, base64, or file");
            }
            if (type == ContentBlockType.IMAGE) {
                var builder = ImageBlockParam.builder();
                switch (source.type()) {
                    case URL -> builder.source(ImageBlockParam.Source.ofUrl(required(source.url(), "source.url")));
                    case FILE -> builder.source(ImageBlockParam.Source.ofFile(required(source.fileId(), "source.fileId")));
                    case BASE64 -> builder.source(
                        Base64ImageSource.builder().data(required(source.data(), "source.data"))
                            .mediaType(Base64ImageSource.MediaType.of(required(source.mediaType(), "source.mediaType"))).build()
                    );
                }
                if (cache != null) {
                    builder.cacheControl(cache);
                }
                return ContentBlockParam.ofImage(builder.build());
            }
            if (type == ContentBlockType.DOCUMENT) {
                var builder = DocumentBlockParam.builder();
                switch (source.type()) {
                    case URL -> builder.source(DocumentBlockParam.Source.ofUrl(required(source.url(), "source.url")));
                    case FILE -> builder.source(DocumentBlockParam.Source.ofFile(required(source.fileId(), "source.fileId")));
                    case BASE64 -> {
                        if (source.mediaType() != null && !"application/pdf".equals(source.mediaType())) {
                            throw new IllegalArgumentException("Base64 documents require mediaType application/pdf");
                        }
                        builder.source(Base64PdfSource.builder().data(required(source.data(), "source.data")).build());
                    }
                }
                Optional.ofNullable(title).ifPresent(builder::title);
                Optional.ofNullable(context).ifPresent(builder::context);
                if (citations != null) {
                    builder.citations(CitationsConfigParam.builder().enabled(Boolean.TRUE.equals(citations.enabled())).build());
                }
                if (cache != null) {
                    builder.cacheControl(cache);
                }
                return ContentBlockParam.ofDocument(builder.build());
            }
            throw new IllegalArgumentException("Content block type must be text, image, or document");
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("%s is required".formatted(field));
        }
        return value;
    }

    public enum ToolChoice {
        AUTO,
        ANY,
        TOOL,
        NONE
    }

    public enum ContentBlockType {
        @JsonProperty("text")
        TEXT,
        @JsonProperty("image")
        IMAGE,
        @JsonProperty("document")
        DOCUMENT
    }

    public enum ContentSourceType {
        @JsonProperty("url")
        URL,
        @JsonProperty("base64")
        BASE64,
        @JsonProperty("file")
        FILE
    }

    public enum CacheControl {
        @JsonProperty("ephemeral")
        EPHEMERAL
    }

    public enum ServiceTier {
        AUTO,
        STANDARD_ONLY
    }

    @Builder
    public record Metadata(@Schema(title = "User ID", description = "Opaque attribution identifier; do not include personal information.") String userId) {
    }

    @Builder
    public record CitationsConfig(@Schema(title = "Enabled", description = "Request citations for the document. Defaults to false.") Boolean enabled) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Citation(
        @Schema(title = "Citation type") String type,
        @JsonAlias("cited_text") @Schema(title = "Cited text") String citedText,
        @JsonAlias("document_index") @Schema(title = "Document index") Long documentIndex,
        @JsonAlias("document_title") @Schema(title = "Document title") String documentTitle,
        @JsonAlias("start_char_index") @Schema(title = "Start character index") Long startCharIndex,
        @JsonAlias("end_char_index") @Schema(title = "End character index") Long endCharIndex,
        @JsonAlias("start_page_number") @Schema(title = "Start page number") Long startPageNumber,
        @JsonAlias("end_page_number") @Schema(title = "End page number") Long endPageNumber,
        @JsonAlias("start_block_index") @Schema(title = "Start block index") Long startBlockIndex,
        @JsonAlias("end_block_index") @Schema(title = "End block index") Long endBlockIndex,
        @JsonAlias("file_id") @Schema(title = "File ID") String fileId,
        @Schema(title = "Source URL") String url,
        @Schema(title = "Source title") String title) {
    }

    public enum ChatMessageType {
        ASSISTANT("assistant"),
        USER("user");

        private final String role;

        ChatMessageType(String role) {
            this.role = role;
        }

        public String role() {
            return role;
        }
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION, defaultImpl = Tool.class)
    @JsonSubTypes(
        {
            @JsonSubTypes.Type(Tool.class),
            @JsonSubTypes.Type(BuiltInTool.class)
        }
    )
    public interface ChatTool {
    }

    @Builder
    public record Tool(
        @Schema(title = "Tool name", description = "Unique identifier for the tool (1-128 characters).") String name,

        @Schema(title = "Tool description", description = "Optional description of what the tool does.") String description,

        @JsonAlias("input_schema")
        @Schema(title = "Input schema", description = "JSON Schema object defining the expected parameters for the tool.") Map<String, Object> inputSchema,
        @Schema(title = "Cache control", description = "Optional ephemeral cache breakpoint on this tool.") CacheControl cacheControl) implements ChatTool {
        public Tool(String name, String description, Map<String, Object> inputSchema) {
            this(name, description, inputSchema, null);
        }
    }

    public enum BuiltInToolType {
        WEB_SEARCH
    }

    @Builder
    public record BuiltInTool(
        @Schema(title = "Tool type", description = "The Anthropic-provided tool to enable.") BuiltInToolType type,

        @Schema(title = "Max uses", description = "Maximum searches per request (WEB_SEARCH only).") Long maxUses,

        @Schema(title = "Allowed domains", description = "Only return results from these domains. Cannot be combined with blockedDomains.") List<String> allowedDomains,

        @Schema(title = "Blocked domains", description = "Never return results from these domains. Cannot be combined with allowedDomains.") List<String> blockedDomains) implements ChatTool {

        com.anthropic.models.messages.ToolUnion toSdkTool() {
            if (type == null) {
                throw new IllegalArgumentException("`type` is required for a built-in tool");
            }
            return switch (type) {
                case WEB_SEARCH -> {
                    boolean hasAllowed = allowedDomains != null && !allowedDomains.isEmpty();
                    boolean hasBlocked = blockedDomains != null && !blockedDomains.isEmpty();
                    if (hasAllowed && hasBlocked) {
                        throw new IllegalArgumentException("`allowedDomains` and `blockedDomains` cannot be used together");
                    }
                    var builder = com.anthropic.models.messages.WebSearchTool20250305.builder();
                    if (maxUses != null) {
                        builder.maxUses(maxUses);
                    }
                    if (hasAllowed) {
                        builder.allowedDomains(allowedDomains);
                    }
                    if (hasBlocked) {
                        builder.blockedDomains(blockedDomains);
                    }
                    yield com.anthropic.models.messages.ToolUnion.ofWebSearchTool20250305(builder.build());
                }
            };
        }
    }

    @Builder
    public record ToolUse(
        @Schema(title = "Tool use ID", description = "Unique identifier for this tool use.") String id,

        @Schema(title = "Tool name", description = "Name of the tool being called.") String name,

        @Schema(title = "Tool input", description = "Parameters passed to the tool as a map.") Map<String, Object> input) {
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "The full response from Claude")
        private String rawResponse;

        @Schema(title = "Assistant text extracted from the response")
        private String outputText;

        @Schema(title = "Tool uses", description = "List of tools that Claude requested to use, if any.")
        private List<ToolUse> toolUses;

        @Schema(title = "Stop reason", description = "The reason the model stopped generating (e.g., end_turn, tool_use, max_tokens).")
        private String stopReason;

        @Schema(title = "Stop sequence", description = "The stop string matched by the model, if any.")
        private String stopSequence;

        @Schema(title = "Thinking", description = "Extended thinking text, when returned by Claude. Redacted thinking is available only in rawResponse.")
        private String thinking;

        @Schema(title = "Citations", description = "Source references attached to response text blocks, in response order.")
        private List<Citation> citations;

        @Schema(title = "Cache creation input tokens", description = "Number of tokens written to the cache.")
        private Long cacheCreationInputTokens;

        @Schema(title = "Cache read input tokens", description = "Number of tokens read from the cache.")
        private Long cacheReadInputTokens;
    }
}
