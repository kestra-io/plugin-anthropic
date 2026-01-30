package io.kestra.plugin.anthropic;

import com.anthropic.models.messages.Message;
import io.kestra.core.models.executions.metrics.Counter;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
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

    @Schema(title = "Anthropic API Key")
    @NotNull
    protected Property<String> apiKey;

    @Schema(
        title = "Model",
        description = "Claude model name to invoke (e.g., claude-3-5-sonnet-20241022); must match an Anthropic model available to your API key."
    )
    @NotNull
    protected Property<String> model;

    @Schema(
        title = "Max Tokens",
        description = "Maximum tokens Anthropic can generate; defaults to 1024 if unset."
    )
    @Builder.Default
    protected Property<Long> maxTokens = Property.ofValue(1024L);

    @Schema(
        title = "Temperature",
        description = "Sampling randomness; range 0.0–1.0 with default 1.0. Lower for deterministic replies."
    )
    @Builder.Default
    protected Property<@Max(1) Double> temperature = Property.ofValue(1.0);

    @Schema(
        title = "Top P",
        description = "Nucleus sampling cap; 0.1 keeps only tokens in top 10% probability mass."
    )
    protected Property<Double> topP;

    @Schema(
        title = "Top K",
        description = "Samples only from the top K tokens for each step; leave unset to let the model choose."
    )
    protected Property<Integer> topK;

    protected void sendMetrics(RunContext runContext, Message message) {
        runContext.metric(Counter.of("usage.input.tokens", message.usage().inputTokens()));
        runContext.metric(Counter.of("usage.output.tokens", message.usage().outputTokens()));
    }
}
