package io.kestra.plugin.anthropic;

import java.util.List;
import java.util.Map;

import com.anthropic.models.messages.MessageCountTokensParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.Model;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
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
@EqualsAndHashCode(callSuper = true)
@Getter
@NoArgsConstructor
@Schema(
    title = "Count input tokens for a Claude request",
    description = "Calls the Anthropic token-count endpoint (`POST /v1/messages/count_tokens`) with the same rendered messages, system prompt, tools, and model as ChatCompletion. Returns only the input token estimate. Does not create a message or call the model. Refer to the [Anthropic Console Settings](https://console.anthropic.com/settings/keys) to create an API key and the [token counting documentation](https://platform.claude.com/docs/en/build-with-claude/token-counting) for more information."
)
@Plugin(
    examples = {
        @Example(
            title = "Count tokens before a chat completion.",
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
            title = "Count tokens for a system prompt and tools.",
            full = true,
            code = """
                id: anthropic_count_tokens_with_tools
                namespace: company.team

                tasks:
                  - id: count_tokens
                    type: io.kestra.plugin.anthropic.CountTokens
                    apiKey: "{{ secret('ANTHROPIC_API_KEY') }}"
                    model: "claude-sonnet-4-6"
                    system: "Extract structured facts. Do not guess missing fields."
                    messages:
                      - type: USER
                        content: |
                          Extract the person from this text:
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
                          required:
                            - name
                            - age
                """
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
    private Property<List<ChatMessage>> messages;

    @Schema(title = "System prompt", description = "Optional system instructions applied to the whole conversation; rendered before the token count.")
    @PluginProperty(group = "advanced")
    private Property<String> system;

    @Schema(
        title = "Tools",
        description = "Optional tools included in the estimate; each entry needs a unique name, an optional description, and an `input_schema` JSON Schema. Tools are counted, not invoked."
    )
    @PluginProperty(group = "destination")
    private Property<List<Tool>> tools;

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rApiKey = runContext.render(apiKey).as(String.class).orElseThrow();
        var rModel = runContext.render(model).as(String.class).orElseThrow();
        var rMessages = runContext.render(messages).asList(ChatMessage.class);
        var rSystem = runContext.render(system).as(String.class);
        var rTools = runContext.render(tools).asList(Tool.class);

        var client = buildClient(rApiKey);

        List<MessageParam> messageParams = rMessages.stream()
            .map(
                message -> MessageParam.builder()
                    .role(MessageParam.Role.of(message.type.role()))
                    .content(message.content)
                    .build()
            )
            .toList();

        var paramsBuilder = MessageCountTokensParams.builder()
            .model(Model.of(rModel))
            .messages(messageParams);

        rSystem.ifPresent(paramsBuilder::system);

        for (Tool tool : rTools) {
            paramsBuilder.addTool(toSdkTool(tool));
        }

        var counted = client.messages().countTokens(paramsBuilder.build());

        return Output.builder()
            .inputTokens(counted.inputTokens())
            .build();
    }

    private com.anthropic.models.messages.Tool toSdkTool(Tool tool) {
        var inputSchemaBuilder = com.anthropic.models.messages.Tool.InputSchema.builder();

        if (tool.inputSchema != null && tool.inputSchema.containsKey("properties")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> properties = (Map<String, Object>) tool.inputSchema.get("properties");
            var propertiesBuilder = com.anthropic.models.messages.Tool.InputSchema.Properties.builder();

            properties.forEach((key, value) ->
            {
                com.anthropic.core.JsonValue jsonValue = com.anthropic.core.JsonValue.from(value);
                propertiesBuilder.putAdditionalProperty(key, jsonValue);
            });

            inputSchemaBuilder.properties(propertiesBuilder.build());

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

        return toolBuilder.build();
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
        @Schema(title = "Tool name", description = "Unique identifier for the tool (1-128 characters).") String name,

        @Schema(title = "Tool description", description = "Optional description of what the tool does.") String description,

        @Schema(title = "Input schema", description = "JSON Schema object defining the expected parameters for the tool.") Map<String, Object> inputSchema) {
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Input tokens",
            description = "Estimated number of input tokens for the given messages, system prompt, and tools."
        )
        private Long inputTokens;
    }
}
