package io.kestra.plugin.anthropic;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.Model;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Metric;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.executions.metrics.Counter;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Send chat messages with Claude",
    description = "Calls the Anthropic Messages API with rendered inputs, optional system prompt, and sampling controls; defaults to maxTokens 1024 and temperature 1.0 while emitting token usage counters. Refer to the [Anthropic Console Settings](https://console.anthropic.com/settings/keys) to create an API key and the [Anthropic API documentation](https://docs.anthropic.com/claude/reference/messages_post) for more information."
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
                      - type: ASSISTANT
                        content: "Quantum computing uses quantum mechanical phenomena like superposition and entanglement to process information differently than classical computers. Instead of bits that are either 0 or 1, quantum computers use quantum bits (qubits) that can exist in multiple states simultaneously."
                      - type: USER
                        content: "That is helpful! Can you give me a practical example of how this could be used in everyday life in the next 10 years?"
                """
        ),
        @Example(
            title = "Structured output using tool use.",
            full = true,
            code = """
                id: anthropic_structured_output
                namespace: company.team

                tasks:
                  - id: extract_data
                    type: io.kestra.plugin.anthropic.ChatCompletion
                    apiKey: "{{ secret('ANTHROPIC_API_KEY') }}"
                    model: "claude-3-5-sonnet-20241022"
                    maxTokens: 1024
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
             )
   }
)
public class ChatCompletion extends AbstractAnthropic implements RunnableTask<ChatCompletion.Output> {

    @Schema(title = "Messages", description = "Ordered chat turns rendered from properties; include at least one USER message.")
    @NotNull
    private Property<List<ChatMessage>> messages;

    @Schema(title = "System prompt", description = "Optional system instructions applied to the whole conversation; rendered before sending to Claude.")
    private Property<String> system;

    @Schema(title = "Tools", description = "List of tools available for Claude to use. Each tool must have a name, description, and input_schema defining the expected parameters.")
    private Property<List<Tool>> tools;

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
        var rTools = runContext.render(tools).asList(Tool.class);

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

        // Add tools if provided
        if (!rTools.isEmpty()) {
            List<com.anthropic.models.messages.ToolUnion> toolParams = rTools.stream()
                .map(tool -> {
                    var inputSchemaBuilder = com.anthropic.models.messages.Tool.InputSchema.builder();

                    // Build input schema from the provided map
                    if (tool.inputSchema != null && tool.inputSchema.containsKey("properties")) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> properties = (Map<String, Object>) tool.inputSchema.get("properties");
                        var propertiesBuilder = com.anthropic.models.messages.Tool.InputSchema.Properties.builder();

                        // Convert properties to JsonValue
                        properties.forEach((key, value) -> {
                            com.anthropic.core.JsonValue jsonValue = com.anthropic.core.JsonValue.from(value);
                            propertiesBuilder.putAdditionalProperty(key, jsonValue);
                        });

                        inputSchemaBuilder.properties(propertiesBuilder.build());

                        // Add required fields if present
                        if (tool.inputSchema.containsKey("required")) {
                            @SuppressWarnings("unchecked")
                            List<String> requiredFields = (List<String>) tool.inputSchema.get("required");
                            inputSchemaBuilder.required(requiredFields);
                        }
                    }

                    var toolBuilder = com.anthropic.models.messages.Tool.builder()
                        .name(tool.name)
                        .inputSchema(inputSchemaBuilder.build());

                    if (tool.description != null && !tool.description.isEmpty()) {
                        toolBuilder.description(tool.description);
                    }

                    return com.anthropic.models.messages.ToolUnion.ofTool(toolBuilder.build());
                })
                .toList();
            paramsBuilder.tools(toolParams);
        }

        paramsBuilder.messages(messageParams);

        var params = paramsBuilder.build();
        var response = client.messages().create(params);

        sendMetrics(runContext, response);

        StringBuilder outputText = new StringBuilder();
        List<ToolUse> toolUses = new ArrayList<>();

        for (ContentBlock block : response.content()) {
            if (block.text().isPresent()) {
                var textBlock = block.text().get();
                outputText.append(textBlock.text());
            } else if (block.toolUse().isPresent()) {
                var toolUseBlock = block.toolUse().get();

                // Convert JsonValue input to Map
                Map<String, Object> inputMap = null;
                try {
                    com.anthropic.core.JsonValue inputJson = toolUseBlock._input();
                    String inputJsonString = JacksonMapper.ofJson().writeValueAsString(inputJson);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> parsedInput = JacksonMapper.ofJson().readValue(inputJsonString, Map.class);
                    inputMap = parsedInput;
                } catch (Exception e) {
                    // If conversion fails, leave input as null
                }

                toolUses.add(ToolUse.builder()
                    .id(toolUseBlock.id())
                    .name(toolUseBlock.name())
                    .input(inputMap)
                    .build());
            }
        }

        return Output.builder()
            .rawResponse(JacksonMapper.ofJson().writeValueAsString(response))
            .outputText(outputText.toString())
            .toolUses(toolUses.isEmpty() ? null : toolUses)
            .stopReason(response.stopReason().toString())
            .build();
    }

    @Builder
    public record ChatMessage(ChatMessageType type, String content) {
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

    @Builder
    public record Tool(
        @Schema(title = "Tool name", description = "Unique identifier for the tool (1-128 characters).")
        String name,

        @Schema(title = "Tool description", description = "Optional description of what the tool does.")
        String description,

        @Schema(title = "Input schema", description = "JSON Schema object defining the expected parameters for the tool.")
        Map<String, Object> inputSchema
    ) {
    }

    @Builder
    public record ToolUse(
        @Schema(title = "Tool use ID", description = "Unique identifier for this tool use.")
        String id,

        @Schema(title = "Tool name", description = "Name of the tool being called.")
        String name,

        @Schema(title = "Tool input", description = "Parameters passed to the tool as a map.")
        Map<String, Object> input
    ) {
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "The full response from Claude.")
        private String rawResponse;

        @Schema(title = "Assistant text extracted from the response.")
        private String outputText;

        @Schema(title = "Tool uses", description = "List of tools that Claude requested to use, if any.")
        private List<ToolUse> toolUses;

        @Schema(title = "Stop reason", description = "The reason the model stopped generating (e.g., end_turn, tool_use, max_tokens).")
        private String stopReason;
    }
}
