package io.kestra.plugin.anthropic;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.Timeout;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.Tool;
import com.fasterxml.jackson.core.type.TypeReference;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.executions.metrics.Counter;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
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
public abstract class AbstractAnthropic extends Task {
    // Tool.InputSchema.Builder already sets type, properties and required, so forwarding them again would serialize them twice.
    private static final Set<String> TYPED_SCHEMA_KEYS = Set.of("type", "properties", "required");

    @Schema(title = "Anthropic API Key")
    @NotNull
    @ToString.Exclude
    @PluginProperty(secret = true, group = "main")
    protected Property<String> apiKey;

    @Schema(
        title = "Base URL",
        description = "Optional Anthropic-compatible endpoint. Defaults to the SDK endpoint. Requests, including the API key, are sent to this host, so set it with `forced: true` in plugin defaults when the API key comes from there."
    )
    @PluginProperty(group = "connection")
    protected Property<String> baseUrl;

    @Schema(title = "Connect timeout", description = "Connection timeout as an ISO-8601 duration. Defaults to PT1M, and zero disables it.")
    @PluginProperty(group = "connection")
    protected Property<Duration> connectTimeout;

    @Schema(
        title = "Read timeout",
        description = "Maximum time for a response as an ISO-8601 duration, both between packets and for the whole call. Defaults to the SDK timeout (PT10M), and zero disables it."
    )
    @PluginProperty(group = "connection")
    protected Property<Duration> readTimeout;

    @Schema(title = "Maximum retries", description = "SDK retries for transient errors, between 0 and 10. Defaults to 2, and 0 disables them.")
    @PluginProperty(group = "reliability")
    @Builder.Default
    protected Property<@Min(0) @Max(10) Integer> maxRetries = Property.ofValue(2);

    protected AnthropicClient buildClient(RunContext runContext, String rApiKey) throws IllegalVariableEvaluationException {
        var builder = AnthropicOkHttpClient.builder().apiKey(rApiKey);
        runContext.render(baseUrl).as(String.class).ifPresent(builder::baseUrl);
        var rConnectTimeout = runContext.render(connectTimeout).as(Duration.class);
        var rReadTimeout = runContext.render(readTimeout).as(Duration.class);
        if (rConnectTimeout.filter(Duration::isNegative).isPresent()) {
            throw new IllegalArgumentException("connectTimeout must be zero or a positive duration such as PT30S.");
        }
        if (rReadTimeout.filter(Duration::isNegative).isPresent()) {
            throw new IllegalArgumentException("readTimeout must be zero or a positive duration such as PT5M.");
        }
        var rMaxRetries = runContext.render(maxRetries).as(Integer.class).orElse(2);
        builder.maxRetries(rMaxRetries);
        if (rConnectTimeout.isPresent() || rReadTimeout.isPresent()) {
            var timeout = Timeout.builder();
            rConnectTimeout.ifPresent(timeout::connect);
            // the SDK caps every call at its request timeout (PT10M), so a longer read timeout must raise it too
            rReadTimeout.ifPresent(value -> timeout.read(value).request(value));
            builder.timeout(timeout.build());
        }
        return builder.build();
    }

    protected void sendMetrics(RunContext runContext, Message message) {
        sendMetrics(
            runContext,
            message.usage().inputTokens(),
            message.usage().outputTokens(),
            message.usage().cacheCreationInputTokens().orElse(null),
            message.usage().cacheReadInputTokens().orElse(null)
        );
    }

    protected void sendMetrics(
        RunContext runContext,
        long inputTokens,
        long outputTokens,
        Long cacheCreationInputTokens,
        Long cacheReadInputTokens) {
        runContext.metric(Counter.of("usage.input.tokens", inputTokens));
        runContext.metric(Counter.of("usage.output.tokens", outputTokens));
        if (cacheCreationInputTokens != null) {
            runContext.metric(Counter.of("usage.cache.creation.tokens", cacheCreationInputTokens));
        }
        if (cacheReadInputTokens != null) {
            runContext.metric(Counter.of("usage.cache.read.tokens", cacheReadInputTokens));
        }
    }

    protected void sendMetrics(RunContext runContext, long inputTokens) {
        runContext.metric(Counter.of("estimate.input.tokens", inputTokens));
    }

    protected static String outputText(Message message) {
        var outputText = new StringBuilder();
        for (ContentBlock block : message.content()) {
            block.text().ifPresent(text -> outputText.append(text.text()));
        }
        return outputText.toString();
    }

    protected static List<ChatCompletion.ToolUse> toolUses(RunContext runContext, Message message) {
        List<ChatCompletion.ToolUse> toolUses = new ArrayList<>();
        for (ContentBlock block : message.content()) {
            block.toolUse().ifPresent(
                toolUse -> toolUses.add(
                    ChatCompletion.ToolUse.builder()
                        .id(toolUse.id())
                        .name(toolUse.name())
                        .input(readToolInput(runContext, toolUse._input()))
                        .build()
                )
            );
        }
        return toolUses.isEmpty() ? null : List.copyOf(toolUses);
    }

    protected static String stopReason(Message message) {
        return message.stopReason().map(StopReason::asString).orElse(null);
    }

    protected static Tool toSdkTool(ChatCompletion.Tool tool) {
        var inputSchemaBuilder = Tool.InputSchema.builder();

        // Build input schema from the provided map
        if (tool.inputSchema() != null && tool.inputSchema().containsKey("properties")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> properties = (Map<String, Object>) tool.inputSchema().get("properties");
            var propertiesBuilder = Tool.InputSchema.Properties.builder();

            // Convert properties to JsonValue
            properties.forEach((key, value) ->
            {
                JsonValue jsonValue = JsonValue.from(value);
                propertiesBuilder.putAdditionalProperty(key, jsonValue);
            });

            inputSchemaBuilder.properties(propertiesBuilder.build());
        }

        if (tool.inputSchema() != null) {
            // Add required fields if present
            if (tool.inputSchema().containsKey("required")) {
                @SuppressWarnings("unchecked")
                List<String> requiredFields = (List<String>) tool.inputSchema().get("required");
                inputSchemaBuilder.required(requiredFields);
            }

            tool.inputSchema().forEach((key, value) ->
            {
                if (!TYPED_SCHEMA_KEYS.contains(key)) {
                    inputSchemaBuilder.putAdditionalProperty(key, JsonValue.from(value));
                }
            });
        }

        var toolBuilder = Tool.builder()
            .name(tool.name())
            .inputSchema(inputSchemaBuilder.build());

        if (tool.description() != null && !tool.description().isEmpty()) {
            toolBuilder.description(tool.description());
        }
        if (tool.cacheControl() != null) {
            toolBuilder.cacheControl(ChatCompletion.cacheControl(tool.cacheControl()));
        }

        return toolBuilder.build();
    }

    private static Map<String, Object> readToolInput(RunContext runContext, JsonValue input) {
        try {
            var json = JacksonMapper.ofJson().writeValueAsString(input);
            return JacksonMapper.ofJson().readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            runContext.logger().warn("Unable to convert Anthropic tool input to a JSON object, so the tool input is null ({}).", e.getClass().getSimpleName());
            return null;
        }
    }
}
