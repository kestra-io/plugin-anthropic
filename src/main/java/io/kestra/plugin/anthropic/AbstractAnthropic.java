package io.kestra.plugin.anthropic;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.StopReason;
import com.fasterxml.jackson.core.type.TypeReference;

import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.executions.metrics.Counter;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
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

    @Schema(title = "Anthropic API Key")
    @NotNull
    @ToString.Exclude
    @PluginProperty(secret = true, group = "main")
    protected Property<String> apiKey;

    protected AnthropicClient buildClient(String rApiKey) {
        return AnthropicOkHttpClient.builder()
            .apiKey(rApiKey)
            .build();
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

    private static Map<String, Object> readToolInput(JsonValue input) {
        try {
            var json = JacksonMapper.ofJson().writeValueAsString(input);
            return JacksonMapper.ofJson().readValue(json, new TypeReference<>() {
            });
        } catch (Exception ignored) {
            return null;
        }
    }
}
