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
        description = "Specifies which Claude model to use for the completion (e.g., 'claude-3-5-sonnet-20241022', 'claude-3-opus-20240229')."
    )
    @NotNull
    protected Property<String> model;

    @Schema(
        title = "Max Tokens",
        description = "The maximum number of tokens to generate in the response."
    )
    @Builder.Default
    protected Property<Long> maxTokens = Property.ofValue(1024L);

    @Schema(
        title = "Temperature",
        description = "Controls randomness in the response. Higher values make output more random."
    )
    @Builder.Default
    protected Property<@Max(1) Double> temperature = Property.ofValue(1.0);

    @Schema(
        title = "Top P",
        description = "Controls diversity via nucleus sampling. 0.1 means only the tokens comprising the top 10% probability mass are considered."
    )
    protected Property<Double> topP;

    @Schema(
        title = "Top K",
        description = "Only sample from the top K options for each subsequent token."
    )
    protected Property<Integer> topK;

    protected void sendMetrics(RunContext runContext, Message message) {
        runContext.metric(Counter.of("usage.input.tokens", message.usage().inputTokens()));
        runContext.metric(Counter.of("usage.output.tokens", message.usage().outputTokens()));
    }
}