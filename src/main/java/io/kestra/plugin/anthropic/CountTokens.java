package io.kestra.plugin.anthropic;

import java.util.List;
import java.util.Map;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
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
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Count input tokens with Claude",
    description = "Calls the Anthropic token counting API with rendered messages, optional system prompt, and tools, and returns the input token count. Refer to the [Anthropic Console Settings](https://console.anthropic.com/settings/keys) to create an API key and the [Anthropic API documentation](https://docs.anthropic.com/claude/reference/messages-count-tokens) for more information."
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
        )
    }
)
public class CountTokens extends AbstractAnthropic implements RunnableTask<CountTokens.Output> {

    static final ThreadLocal<String> baseUrlOverride = new ThreadLocal<>();

    @Schema(
        title = "Model",
        description = "Claude model name to invoke (e.g., claude-sonnet-4-6); must match an Anthropic model available to your API key."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> model;

    @Schema(title = "Messages", description = "Ordered chat turns rendered from properties; include at least one USER message.")
    @NotNull
    @PluginProperty(group = "main")
    private Property<List<ChatMessage>> messages;

    @Schema(title = "System prompt", description = "Optional system instructions applied to the whole conversation; rendered before sending to Claude.")
    @PluginProperty(group = "advanced")
    private Property<String> system;

    @Schema(
        title = "Tools",
        description = "Optional tools Claude can invoke; each entry needs a unique name, an optional description, and an `input_schema` JSON Schema that defines the parameters the tool accepts."
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

            .model(Model.of(rModel));

        rSystem.ifPresent(paramsBuilder::system);

        // Add tools if provided
        if (!rTools.isEmpty()) {
            List<com.anthropic.models.messages.MessageCountTokensTool> toolParams = rTools.stream()
                .map(tool ->
                {
                    var inputSchemaBuilder = com.anthropic.models.messages.Tool.InputSchema.builder();

                    // Build input schema from the provided map
                    if (tool.inputSchema != null && tool.inputSchema.containsKey("properties")) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> properties = (Map<String, Object>) tool.inputSchema.get("properties");
                        var propertiesBuilder = com.anthropic.models.messages.Tool.InputSchema.Properties.builder();

                        // Convert properties to JsonValue
                        properties.forEach((key, value) ->
                        {
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

                    return com.anthropic.models.messages.MessageCountTokensTool.ofTool(toolBuilder.build());
                })
                .toList();
            paramsBuilder.tools(toolParams);
        }

        paramsBuilder.messages(messageParams);

        var params = paramsBuilder.build();
        var response = client.messages().countTokens(params);

        return Output.builder()
            .inputTokens(response.inputTokens())
            .build();
    }

    @Override
    protected AnthropicClient buildClient(String rApiKey) {
        String baseUrl = baseUrlOverride.get();
        if (baseUrl == null) {
            return super.buildClient(rApiKey);
        }

        return AnthropicOkHttpClient.builder()
            .apiKey(rApiKey)
            .baseUrl(baseUrl)
            .maxRetries(0)
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
        @Schema(title = "Tool name", description = "Unique identifier for the tool (1-128 characters).") String name,

        @Schema(title = "Tool description", description = "Optional description of what the tool does.") String description,

        @Schema(title = "Input schema", description = "JSON Schema object defining the expected parameters for the tool.") Map<String, Object> inputSchema) {
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Input tokens", description = "Number of input tokens for the given messages, system prompt, and tools.")
        private Long inputTokens;
    }
}
