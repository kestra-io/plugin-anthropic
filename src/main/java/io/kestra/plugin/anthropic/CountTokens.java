package io.kestra.plugin.anthropic;

import java.util.List;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.MessageCountTokensParams;
import com.anthropic.models.messages.MessageCountTokensTool;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.Model;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Metric;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.executions.metrics.Counter;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;

import io.swagger.v3.oas.annotations.media.Schema;
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
    title = "Count input tokens for a Claude request",
    description = "Calls the Anthropic token-count endpoint (`POST /v1/messages/count_tokens`) with the same rendered messages, system prompt, tools, and model as ChatCompletion. Returns only `inputTokens`. Does not create a message or call the model. A later task can read `{{ outputs.<task-id>.inputTokens }}` to gate on cost or context size before ChatCompletion. Refer to the [Anthropic Console Settings](https://console.anthropic.com/settings/keys) to create an API key and the [Anthropic API documentation](https://docs.anthropic.com/claude/reference/messages-count-tokens) for more information."
)
@Plugin(
    examples = {
        @Example(
            title = "Count tokens using Claude.",
            full = true,
            code = """
                id: anthropic_count_tokens
                namespace: company.team

                tasks:
                  - id: count_tokens
                    type: io.kestra.plugin.anthropic.CountTokens
                    apiKey: "{{ secret('ANTHROPIC_API_KEY') }}"
                    model: "claude-sonnet-4-6"
                    messages:
                      - type: USER
                        content: "What is the capital of Japan? Answer with a unique word and without any punctuation."
                """
        ),
        @Example(
            title = "Count tokens with tools",
            full = true,
            code = """
                id: anthropic_count_tokens_with_tools
                namespace: company.team

                tasks:
                  - id: count_tokens
                    type: io.kestra.plugin.anthropic.CountTokens
                    apiKey: "{{ secret('ANTHROPIC_API_KEY') }}"
                    model: "claude-sonnet-4-6"
                    messages:
                      - type: USER
                        content: |
                          Extract the following information from this text:
                          "John Doe is 30 years old and works as a Software Engineer in San Francisco."
                    tools:
                      - name: extract_person_info
                        description: "Extract structured information about a person"
                        input_schema:
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
            title = "Count tokens, then complete only when the prompt fits.",
            full = true,
            code = """
                id: anthropic_count_tokens_before_completion
                namespace: company.team

                tasks:
                  - id: count_tokens
                    type: io.kestra.plugin.anthropic.CountTokens
                    apiKey: "{{ secret('ANTHROPIC_API_KEY') }}"
                    model: "claude-sonnet-4-6"
                    system: "Answer in one word."
                    messages:
                      - type: USER
                        content: "What is the capital of Japan? Answer with a unique word and without any punctuation."

                  - id: chat_completion
                    type: io.kestra.plugin.anthropic.ChatCompletion
                    runIf: "{{ outputs.count_tokens.inputTokens < 8000 }}"
                    apiKey: "{{ secret('ANTHROPIC_API_KEY') }}"
                    model: "claude-sonnet-4-6"
                    maxTokens: 1024
                    system: "Answer in one word."
                    messages:
                      - type: USER
                        content: "What is the capital of Japan? Answer with a unique word and without any punctuation."
                """
        )
    },
    metrics = {
        @Metric(
            name = "usage.input.tokens",
            type = Counter.TYPE,
            unit = "token",
            description = "Number of input tokens estimated for the request."
        )
    }
)
public class CountTokens extends AbstractAnthropic implements RunnableTask<CountTokens.Output> {

    @Schema(
        title = "Model",
        description = "Claude model name used to estimate tokens (e.g., claude-sonnet-4-6); must match an Anthropic model available to your API key."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> model;

    @Schema(title = "Messages", description = "Ordered chat turns rendered from properties; include at least one USER message.")
    @NotNull
    @PluginProperty(group = "main")
    private Property<List<ChatCompletion.ChatMessage>> messages;

    @Schema(title = "System prompt", description = "Optional system instructions applied to the whole conversation; rendered before the token count.")
    @PluginProperty(group = "advanced")
    private Property<String> system;

    @Schema(
        title = "Tools",
        description = "Optional tools included in the estimate; each entry needs a unique name, an optional description, and an `input_schema` JSON Schema that defines the parameters the tool accepts. Tools are counted, not invoked."
    )
    @PluginProperty(group = "destination")
    private Property<List<ChatCompletion.Tool>> tools;

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rApiKey = runContext.render(apiKey).as(String.class)
            .map(String::strip)
            .filter(value -> !value.isBlank())
            .orElseThrow(() -> new IllegalArgumentException("apiKey is required"));
        var rModel = runContext.render(model).as(String.class)
            .map(String::strip)
            .filter(value -> !value.isBlank())
            .orElseThrow(() -> new IllegalArgumentException("model is required"));
        if (messages == null) {
            throw new IllegalArgumentException("messages is required");
        }
        var rMessages = runContext.render(messages).asList(ChatCompletion.ChatMessage.class);
        if (rMessages.isEmpty()) {
            throw new IllegalArgumentException("messages must contain at least one item");
        }
        var rSystem = runContext.render(system).as(String.class);
        var rTools = runContext.render(tools).asList(ChatCompletion.Tool.class);

        var client = anthropicClient(rApiKey);
        try {
            List<MessageParam> messageParams = rMessages.stream()
                .map(
                    message -> MessageParam.builder()
                        .role(MessageParam.Role.of(message.type().role()))
                        .content(message.content())
                        .build()
                )
                .toList();

            var paramsBuilder = MessageCountTokensParams.builder()

                .model(Model.of(rModel));

            rSystem.map(String::strip).filter(text -> !text.isBlank()).ifPresent(paramsBuilder::system);

            // Add tools if provided
            if (!rTools.isEmpty()) {
                List<MessageCountTokensTool> toolParams = rTools.stream()
                    .map(tool -> MessageCountTokensTool.ofTool(toSdkTool(tool)))
                    .toList();
                paramsBuilder.tools(toolParams);
            }

            paramsBuilder.messages(messageParams);

            var params = paramsBuilder.build();
            var response = client.messages().countTokens(params);

            sendMetrics(runContext, response.inputTokens());

            return Output.builder()
                .inputTokens(response.inputTokens())
                .build();
        } finally {
            client.close();
        }
    }

    protected AnthropicClient anthropicClient(String rApiKey) {
        return buildClient(rApiKey);
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Input tokens", description = "Estimated number of input tokens for the given messages, system prompt, and tools.")
        private Long inputTokens;
    }
}
