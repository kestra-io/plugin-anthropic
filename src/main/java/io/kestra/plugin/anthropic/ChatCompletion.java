package io.kestra.plugin.anthropic;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.Model;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.util.List;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Complete a chat using the Anthropic Claude API.",
    description = "Send messages to Claude models and receive responses. Refer to the [Anthropic Console Settings](https://console.anthropic.com/settings/keys) to create an API key and the [Anthropic API documentation](https://docs.anthropic.com/claude/reference/messages_post) for more information."
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
                    model: "claude-3-5-sonnet-20241022"
                    maxTokens: 1024
                    messages:
                      - type: USER
                        content: "What is the capital of Japan? Answer with a unique word and without any punctuation."
                """
        ),
        @Example(
            title = "Code generation using Claude.",
            full = true,
            code = """
                id: anthropic_code_generation
                namespace: company.team

                tasks:
                  - id: code_generation
                    type: io.kestra.plugin.anthropic.ChatCompletion
                    apiKey: "{{ secret('ANTHROPIC_API_KEY') }}"
                    model: "claude-3-5-sonnet-20241022"
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
            title = "Conversation with follow-up context.",
            full = true,
            code = """
                id: anthropic_context_conversation
                namespace: company.team

                tasks:
                  - id: code_generation
                    type: io.kestra.plugin.anthropic.ChatCompletion
                    apiKey: "{{ secret('ANTHROPIC_API_KEY') }}"
                    model: "claude-3-5-sonnet-20241022"
                    maxTokens: 800
                    temperature: 0.5
                    messages:
                      - type: USER
                        content: "Explain quantum computing in simple terms."
                      - type: AI
                        content: "Quantum computing uses quantum mechanical phenomena like superposition and entanglement to process information differently than classical computers. Instead of bits that are either 0 or 1, quantum computers use quantum bits (qubits) that can exist in multiple states simultaneously."
                      - type: USER
                        content: "That is helpful! Can you give me a practical example of how this could be used in everyday life in the next 10 years?"
                """
        ),
    }
)
public class ChatCompletion extends AbstractAnthropic implements RunnableTask<ChatCompletion.Output> {

    @Schema(title = "Messages", description = "List of messages to send to Claude")
    @NotNull
    private Property<List<ChatMessage>> messages;

    @Schema(title = "System prompt", description = "System prompt to set the behavior of the assistant")
    private Property<String> system;

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rApiKey = runContext.render(apiKey).as(String.class).orElseThrow();
        var rModel = runContext.render(model).as(String.class).orElseThrow();
        var rMaxTokens = runContext.render(maxTokens).as(Long.class).orElse(1024L);
        var rMessages = runContext.render(messages).asList(ChatMessage.class);
        var rTemperature = runContext.render(temperature).as(Double.class).orElse(1.0);
        var rTopP = runContext.render(topP).as(Double.class);
        var rTopK = runContext.render(topK).as(Integer.class);
        var rSystem = runContext.render(system).as(String.class);

        var client = AnthropicOkHttpClient.builder()
            .apiKey(rApiKey)
            .build();

        List<MessageParam> messageParams = rMessages.stream()
            .map(message -> MessageParam.builder()
                .role(MessageParam.Role.of(message.type.role()))
                .content(message.content)
                .build())
            .toList();

        var paramsBuilder = MessageCreateParams.builder()

            .model(Model.of(rModel))
            .maxTokens(rMaxTokens)
            .temperature(rTemperature);

        rSystem.ifPresent(paramsBuilder::system);
        rTopP.ifPresent(paramsBuilder::topP);
        rTopK.ifPresent(paramsBuilder::topK);

        paramsBuilder.messages(messageParams);

        var params = paramsBuilder.build();
        var response = client.messages().create(params);

        sendMetrics(runContext, response);

        StringBuilder outputText = new StringBuilder();
        for (ContentBlock block : response.content()) {
            if (block.text().isPresent()) {
                var textBlock = block.text().get();
                outputText.append(textBlock.text());
            }
        }

        return Output.builder()
            .rawResponse(JacksonMapper.ofJson().writeValueAsString(response))
            .outputText(outputText.toString())
            .build();
    }

    @Builder
    public record ChatMessage(ChatMessageType type, String content) {
    }

    public enum ChatMessageType {
        AI("assistant"),
        USER("user");

        private final String role;

        ChatMessageType(String role) {
            this.role = role;
        }

        public String role() {
            return role;
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "The full response from Claude.")
        private String rawResponse;

        @Schema(title = "The content generated by Claude.")
        private String outputText;
    }
}