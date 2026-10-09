package io.kestra.plugin.anthropic;

import java.util.List;
import java.util.Map;

import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.Model;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Metric;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
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

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Send chat messages with Claude",
    description = "Calls the Anthropic Messages API with rendered inputs, optional system prompt, and sampling controls; defaults to maxTokens 1024 while emitting token usage counters. Refer to the [Anthropic Console Settings](https://console.anthropic.com/settings/keys) to create an API key and the [Anthropic API documentation](https://docs.anthropic.com/claude/reference/messages_post) for more information."
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
                    model: "claude-sonnet-4-6"
                    maxTokens: 1024
                    messages:
                      - type: USER
                        content: "What is the capital of Japan? Answer with a unique word and without any punctuation."
                """
        ),
        @Example(
            title = "Code generation using Claude",
            full = true,
            code = """
                id: anthropic_code_generation
                namespace: company.team

                tasks:
                  - id: code_generation
                    type: io.kestra.plugin.anthropic.ChatCompletion
                    apiKey: "{{ secret('ANTHROPIC_API_KEY') }}"
                    model: "claude-sonnet-4-6"
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
            title = "Conversation with follow-up context",
            full = true,
            code = """
                id: anthropic_context_conversation
                namespace: company.team

                tasks:
                  - id: code_generation
                    type: io.kestra.plugin.anthropic.ChatCompletion
                    apiKey: "{{ secret('ANTHROPIC_API_KEY') }}"
                    model: "claude-sonnet-4-6"
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
            title = "Chat completion with prompt caching",
            full = true,
            code = """
                id: anthropic_cached_chat
                namespace: company.team

                tasks:
                  - id: cached_chat
                    type: io.kestra.plugin.anthropic.ChatCompletion
                    apiKey: "{{ secret('ANTHROPIC_API_KEY') }}"
                    model: "claude-sonnet-4-6"
                    maxTokens: 1024
                    promptCaching: true
                    system: "You are a helpful assistant with extensive knowledge about Kestra, the open-source orchestration platform."
                    messages:
                      - type: USER
                        content: "What are the key features of Kestra?"
                """
        ),
        @Example(
            title = "Tool-assisted structured extraction",
            full = true,
            code = """
                id: anthropic_structured_output
                namespace: company.team

                tasks:
                  - id: extract_data
                    type: io.kestra.plugin.anthropic.ChatCompletion
                    apiKey: "{{ secret('ANTHROPIC_API_KEY') }}"
                    model: "claude-sonnet-4-6"
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
        @Example(
            title = "Chat completion with Anthropic web search",
            full = true,
            code = """
                id: anthropic_web_search
                namespace: company.team

                tasks:
                  - id: search
                    type: io.kestra.plugin.anthropic.ChatCompletion
                    apiKey: "{{ secret('ANTHROPIC_API_KEY') }}"
                    model: "claude-sonnet-4-6"
                    maxTokens: 2048
                    messages:
                      - type: USER
                        content: "Summarize recent news about open-source orchestration tools."
                    tools:
                      - type: WEB_SEARCH
                        maxUses: 3
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
        ),
        @Metric(
            name = "usage.cache.creation.tokens",
            type = Counter.TYPE,
            unit = "token",
            description = "Number of tokens written to the prompt cache."
        ),
        @Metric(
            name = "usage.cache.read.tokens",
            type = Counter.TYPE,
            unit = "token",
            description = "Number of tokens read from the prompt cache."
        )
    }
)
public class ChatCompletion extends AbstractAnthropicChat implements RunnableTask<ChatCompletion.Output> {

    @Schema(title = "Messages", description = "Ordered chat turns rendered from properties; include at least one USER message.")
    @NotNull
    @PluginProperty(group = "main")
    private Property<List<ChatMessage>> messages;

    @Schema(title = "System prompt", description = "Optional system instructions applied to the whole conversation; rendered before sending to Claude.")
    @PluginProperty(group = "advanced")
    private Property<String> system;

    @Schema(
        title = "Tools",
        description = "Tools Claude can use. Each entry is either a user-defined tool (`name`, optional `description`, `inputSchema` JSON Schema) or an Anthropic built-in tool selected with `type` (currently `WEB_SEARCH`, with optional `maxUses`, `allowedDomains` and `blockedDomains`). With built-in tools, Anthropic can end a long-running turn with `stopReason` `pause_turn`; the task does not resume it, so `outputText` may then be partial."
    )
    @PluginProperty(group = "destination")
    private Property<List<ChatTool>> tools;

    @Schema(
        title = "Prompt caching",
        description = """
            Enable prompt caching to reduce costs and latency for repetitive prompts.
            When enabled, the API automatically caches the longest cacheable prefix of the request.
            Cached tokens are billed at a reduced rate.
            See the [Anthropic prompt caching documentation](https://docs.anthropic.com/en/docs/build-with-claude/prompt-caching) for details."""
    )
    @Builder.Default
    private Property<Boolean> promptCaching = Property.ofValue(false);

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rApiKey = runContext.render(apiKey).as(String.class).orElseThrow();
        var rModel = runContext.render(model).as(String.class).orElseThrow();
        var rMaxTokens = runContext.render(maxTokens).as(Long.class).orElse(1024L);
        var rMessages = runContext.render(messages).asList(ChatMessage.class);
        var rTemperature = runContext.render(temperature).as(Double.class);
        var rTopP = runContext.render(topP).as(Double.class);
        var rTopK = runContext.render(topK).as(Integer.class);
        var rSystem = runContext.render(system).as(String.class);
        var rAllTools = runContext.render(tools).asList(ChatTool.class);
        var rTools = rAllTools.stream().filter(Tool.class::isInstance).map(Tool.class::cast).toList();
        var rBuiltInTools = rAllTools.stream().filter(BuiltInTool.class::isInstance).map(BuiltInTool.class::cast).toList();
        var rPromptCaching = runContext.render(promptCaching).as(Boolean.class).orElse(false);

        var client = buildClient(rApiKey);

        List<MessageParam> messageParams = rMessages.stream()
            .map(
                message -> MessageParam.builder()
                    .role(MessageParam.Role.of(message.type.role()))
                    .content(message.content)
                    .build()
            )
            .toList();

        var paramsBuilder = MessageCreateParams.builder()

            .model(Model.of(rModel))
            .maxTokens(rMaxTokens);

        rSystem.ifPresent(paramsBuilder::system);
        rTemperature.ifPresent(paramsBuilder::temperature);
        rTopP.ifPresent(paramsBuilder::topP);
        rTopK.ifPresent(paramsBuilder::topK);

        // Add tools if provided
        if (!rTools.isEmpty()) {
            List<com.anthropic.models.messages.ToolUnion> toolParams = rTools.stream()
                .map(tool -> com.anthropic.models.messages.ToolUnion.ofTool(toSdkTool(tool)))
                .toList();
            paramsBuilder.tools(toolParams);
        }

        rBuiltInTools.forEach(tool -> paramsBuilder.addTool(tool.toSdkTool()));

        paramsBuilder.messages(messageParams);

        // Enable prompt caching if requested
        if (rPromptCaching) {
            paramsBuilder.cacheControl(CacheControlEphemeral.builder().build());
        }

        var params = paramsBuilder.build();
        var response = client.messages().create(params);

        sendMetrics(runContext, response);

        return Output.builder()
            .rawResponse(JacksonMapper.ofJson().writeValueAsString(response))
            .outputText(outputText(response))
            .toolUses(toolUses(response))
            .stopReason(stopReason(response))
            .cacheCreationInputTokens(response.usage().cacheCreationInputTokens().orElse(null))
            .cacheReadInputTokens(response.usage().cacheReadInputTokens().orElse(null))
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

    @JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION, defaultImpl = Tool.class)
    @JsonSubTypes(
        {
            @JsonSubTypes.Type(Tool.class),
            @JsonSubTypes.Type(BuiltInTool.class)
        }
    )
    public interface ChatTool {
    }

    @Builder
    public record Tool(
        @Schema(title = "Tool name", description = "Unique identifier for the tool (1-128 characters).") String name,

        @Schema(title = "Tool description", description = "Optional description of what the tool does.") String description,

        @Schema(title = "Input schema", description = "JSON Schema object defining the expected parameters for the tool.") Map<String, Object> inputSchema) implements ChatTool {
    }

    public enum BuiltInToolType {
        WEB_SEARCH
    }

    @Builder
    public record BuiltInTool(
        @Schema(title = "Tool type", description = "The Anthropic-provided tool to enable.") BuiltInToolType type,

        @Schema(title = "Max uses", description = "Maximum searches per request (WEB_SEARCH only).") Long maxUses,

        @Schema(title = "Allowed domains", description = "Only return results from these domains. Cannot be combined with blockedDomains.") List<String> allowedDomains,

        @Schema(title = "Blocked domains", description = "Never return results from these domains. Cannot be combined with allowedDomains.") List<String> blockedDomains) implements ChatTool {

        com.anthropic.models.messages.ToolUnion toSdkTool() {
            if (type == null) {
                throw new IllegalArgumentException("`type` is required for a built-in tool");
            }
            return switch (type) {
                case WEB_SEARCH -> {
                    boolean hasAllowed = allowedDomains != null && !allowedDomains.isEmpty();
                    boolean hasBlocked = blockedDomains != null && !blockedDomains.isEmpty();
                    if (hasAllowed && hasBlocked) {
                        throw new IllegalArgumentException("`allowedDomains` and `blockedDomains` cannot be used together");
                    }
                    var builder = com.anthropic.models.messages.WebSearchTool20250305.builder();
                    if (maxUses != null) {
                        builder.maxUses(maxUses);
                    }
                    if (hasAllowed) {
                        builder.allowedDomains(allowedDomains);
                    }
                    if (hasBlocked) {
                        builder.blockedDomains(blockedDomains);
                    }
                    yield com.anthropic.models.messages.ToolUnion.ofWebSearchTool20250305(builder.build());
                }
            };
        }
    }

    @Builder
    public record ToolUse(
        @Schema(title = "Tool use ID", description = "Unique identifier for this tool use.") String id,

        @Schema(title = "Tool name", description = "Name of the tool being called.") String name,

        @Schema(title = "Tool input", description = "Parameters passed to the tool as a map.") Map<String, Object> input) {
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "The full response from Claude")
        private String rawResponse;

        @Schema(title = "Assistant text extracted from the response")
        private String outputText;

        @Schema(title = "Tool uses", description = "List of tools that Claude requested to use, if any.")
        private List<ToolUse> toolUses;

        @Schema(title = "Stop reason", description = "The reason the model stopped generating (e.g., end_turn, tool_use, max_tokens).")
        private String stopReason;

        @Schema(title = "Cache creation input tokens", description = "Number of tokens written to the cache.")
        private Long cacheCreationInputTokens;

        @Schema(title = "Cache read input tokens", description = "Number of tokens read from the cache.")
        private Long cacheReadInputTokens;
    }
}
