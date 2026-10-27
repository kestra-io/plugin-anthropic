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

import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.executions.metrics.Counter;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
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
public abstract class AbstractAnthropic extends Task {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(AbstractAnthropic.class);

    // Tool.InputSchema.Builder already sets type, properties and required, so forwarding them again would serialize them twice.
    private static final Set<String> TYPED_SCHEMA_KEYS = Set.of("type", "properties", "required");

    @Schema(title = "Anthropic API Key")
    @NotNull
    @ToString.Exclude
    @PluginProperty(secret = true, group = "main")
    protected Property<String> apiKey;

    @Schema(title = "Base URL", description = "Optional Anthropic-compatible endpoint. Defaults to the SDK endpoint.")
    @PluginProperty(group = "connection")
    protected Property<String> baseUrl;

    @Schema(title = "Connect timeout", description = "Connection timeout as an ISO-8601 duration. Defaults to PT1M; zero disables the timeout.")
    @PluginProperty(group = "connection")
    protected Property<Duration> connectTimeout;

    @Schema(title = "Read timeout", description = "Maximum time between response packets as an ISO-8601 duration. Defaults to the SDK timeout (PT10M); zero disables the timeout.")
    @PluginProperty(group = "connection")
    protected Property<Duration> readTimeout;

    @Schema(title = "Maximum retries", description = "SDK retries for transient errors. Defaults to 2; set 0 to disable. Must be non-negative.")
    @PluginProperty(group = "reliability")
    @Builder.Default
    protected Property<@Min(0) Integer> maxRetries = Property.ofValue(2);

    protected AnthropicClient buildClient(RunContext runContext, String rApiKey) throws Exception {
        var builder = AnthropicOkHttpClient.builder().apiKey(rApiKey);
        runContext.render(baseUrl).as(String.class).ifPresent(builder::baseUrl);
        var connect = runContext.render(connectTimeout).as(Duration.class);
        var read = runContext.render(readTimeout).as(Duration.class);
        if (connect.filter(Duration::isNegative).isPresent() || read.filter(Duration::isNegative).isPresent()) {
            throw new IllegalArgumentException("connectTimeout and readTimeout must be non-negative durations");
        }
        var retries = runContext.render(maxRetries).as(Integer.class).orElse(2);
        if (retries < 0) {
            throw new IllegalArgumentException("maxRetries must be non-negative");
        }
        builder.maxRetries(retries);
        if (connect.isPresent() || read.isPresent()) {
            var timeout = Timeout.builder();
            connect.ifPresent(timeout::connect);
            read.ifPresent(timeout::read);
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

    protected static List<ChatCompletion.ToolUse> toolUses(Message message) {
        List<ChatCompletion.ToolUse> toolUses = new ArrayList<>();
        for (ContentBlock block : message.content()) {
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

    private static Map<String, Object> readToolInput(JsonValue input) {
        try {
            var json = JacksonMapper.ofJson().writeValueAsString(input);
            return JacksonMapper.ofJson().readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            LOGGER.warn("Unable to convert Anthropic tool input to a JSON object; tool input will be null ({}).", e.getClass().getSimpleName());
            return null;
        }
    }
}
