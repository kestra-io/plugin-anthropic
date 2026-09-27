package io.kestra.plugin.anthropic;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;

import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.executions.metrics.Counter;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;

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
        runContext.metric(Counter.of("usage.input.tokens", message.usage().inputTokens()));
        runContext.metric(Counter.of("usage.output.tokens", message.usage().outputTokens()));
        message.usage().cacheCreationInputTokens().ifPresent(
            tokens -> runContext.metric(Counter.of("usage.cache.creation.tokens", tokens))
        );
        message.usage().cacheReadInputTokens().ifPresent(
            tokens -> runContext.metric(Counter.of("usage.cache.read.tokens", tokens))
        );
    }
}
