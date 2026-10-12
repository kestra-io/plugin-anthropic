package io.kestra.plugin.anthropic;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.anthropic.core.ObjectMappers;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.json.JsonMapper;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.serializers.JacksonMapper;

import jakarta.inject.Inject;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
public class ChatCompletionTest {

    private final String ANTHROPIC_API_KEY = System.getenv("ANTHROPIC_API_KEY");

    @Inject
    private RunContextFactory runContextFactory;

    @EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".*")
    @Test
    void shouldGetResultsWithAnthropicChatCompletion() throws Exception {
        var runContext = runContextFactory.of(
            Map.of(
                "apiKey", ANTHROPIC_API_KEY,
                "model", "claude-sonnet-4-6",
                "messages", List.of(
                    ChatCompletion.ChatMessage.builder()
                        .type(ChatCompletion.ChatMessageType.USER)
                        .content("What is the capital of France? Answer just the name.")
                        .build()
                )
            )
        );

        var task = ChatCompletion.builder()
            .apiKey(Property.ofExpression("{{ apiKey }}"))
            .model(Property.ofExpression("{{ model }}"))
            .messages(Property.ofExpression("{{ messages }}"))
            .build();

        var output = task.run(runContext);

        assertThat(output, notNullValue());
        assertThat(output.getRawResponse(), notNullValue());
        assertThat(output.getRawResponse(), containsStringIgnoringCase("paris"));
    }

    @EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".*")
    @Test
    void shouldUseToolsForStructuredOutput() throws Exception {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put(
            "properties", Map.of(
                "name", Map.of("type", "string"),
                "age", Map.of("type", "integer")
            )
        );
        schema.put("required", List.of("name", "age"));

        var tool = ChatCompletion.Tool.builder()
            .name("extract_person")
            .description("Extract person info")
            .inputSchema(schema)
            .build();

        var runContext = runContextFactory.of(
            Map.of(
                "apiKey", ANTHROPIC_API_KEY,
                "model", "claude-sonnet-4-6",
                "messages", List.of(
                    ChatCompletion.ChatMessage.builder()
                        .type(ChatCompletion.ChatMessageType.USER)
                        .content("John is 25 years old")
                        .build()
                ),
                "tools", List.of(tool)
            )
        );

        var task = ChatCompletion.builder()
            .apiKey(Property.ofExpression("{{ apiKey }}"))
            .model(Property.ofExpression("{{ model }}"))
            .messages(Property.ofExpression("{{ messages }}"))
            .tools(Property.ofExpression("{{ tools }}"))
            .build();

        var output = task.run(runContext);

        assertThat(output.getStopReason(), is("tool_use"));
        assertThat(output.getToolUses(), hasSize(1));
        assertThat(output.getToolUses().get(0).name(), is("extract_person"));
    }

    @EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".*")
    @Test
    void shouldSupportPromptCaching() throws Exception {
        var runContext = runContextFactory.of(
            Map.of(
                "apiKey", ANTHROPIC_API_KEY,
                "model", "claude-sonnet-4-6",
                "messages", List.of(
                    ChatCompletion.ChatMessage.builder()
                        .type(ChatCompletion.ChatMessageType.USER)
                        .content("What is the capital of France? Answer just the name.")
                        .build()
                )
            )
        );

        var task = ChatCompletion.builder()
            .apiKey(Property.ofExpression("{{ apiKey }}"))
            .model(Property.ofExpression("{{ model }}"))
            .messages(Property.ofExpression("{{ messages }}"))
            .promptCaching(Property.ofValue(true))
            .build();

        var output = task.run(runContext);

        assertThat(output, notNullValue());
        assertThat(output.getOutputText(), containsStringIgnoringCase("paris"));
        assertThat(output.getStopReason(), is("end_turn"));
    }

    @Test
    void toolsList_deserializesBothVariants() {
        var raw = List.of(
            Map.of("name", "extract_person_info", "inputSchema", Map.of("type", "object")),
            Map.of("type", "WEB_SEARCH", "maxUses", 3)
        );

        List<ChatCompletion.ChatTool> tools = JacksonMapper.ofJson()
            .convertValue(raw, new TypeReference<List<ChatCompletion.ChatTool>>() {
            });

        assertThat(tools.get(0), instanceOf(ChatCompletion.Tool.class));
        assertThat(tools.get(1), instanceOf(ChatCompletion.BuiltInTool.class));
    }

    @Test
    void shouldAcceptInputSchemaAliasWhenRenderingTools() throws Exception {
        Map<String, Object> schema = Map.of(
            "type", "object",
            "properties", Map.of("name", Map.of("type", "string")),
            "required", List.of("name")
        );
        var raw = List.of(
            Map.of("name", "extract_person_info", "input_schema", schema)
        );

        Property<List<ChatCompletion.ChatTool>> property = Property.ofExpression("{{ tools }}");
        List<ChatCompletion.ChatTool> tools = runContextFactory.of(Map.of("tools", raw))
            .render(property)
            .asList(ChatCompletion.ChatTool.class);

        assertThat(tools.get(0), instanceOf(ChatCompletion.Tool.class));
        assertThat(((ChatCompletion.Tool) tools.get(0)).inputSchema(), is(schema));
    }

    @Test
    void shouldKeepEveryTopLevelSchemaKeywordWhenConvertingCustomTool() throws Exception {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.put(
            "properties", Map.of(
                "orderId", Map.of("type", "integer"),
                "customer", Map.of("$ref", "#/$defs/Customer")
            )
        );
        schema.put("required", List.of("orderId", "customer"));
        schema.put(
            "$defs", Map.of(
                "Customer", Map.of(
                    "type", "object",
                    "additionalProperties", false,
                    "properties", Map.of(
                        "name", Map.of("type", "string"),
                        "email", Map.of("type", "string")
                    ),
                    "required", List.of("name", "email")
                )
            )
        );

        var tool = ChatCompletion.Tool.builder()
            .name("record_order")
            .description("Record an order and its customer")
            .inputSchema(schema)
            .build();

        var json = ObjectMappers.jsonMapper().writeValueAsString(AbstractAnthropic.toSdkTool(tool).inputSchema());
        var strictMapper = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
        Map<String, Object> serialized = assertDoesNotThrow(
            () -> strictMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            })
        );

        assertThat(serialized.keySet(), containsInAnyOrder("type", "additionalProperties", "properties", "required", "$defs"));
        assertThat(serialized, is(schema));
    }

    @Test
    void webSearchBuiltInTool_mapsToSdkTool() {
        var union = new ChatCompletion.BuiltInTool(
            ChatCompletion.BuiltInToolType.WEB_SEARCH, 3L, List.of("anthropic.com"), null
        ).toSdkTool();

        assertThat(union.isWebSearchTool20250305(), is(true));
        var tool = union.asWebSearchTool20250305();
        assertThat(tool.maxUses().orElseThrow(), is(3L));
        assertThat(tool.allowedDomains().orElseThrow(), contains("anthropic.com"));
    }

    @Test
    void webSearchBuiltInTool_rejectsAllowedAndBlockedTogether() {
        var tool = new ChatCompletion.BuiltInTool(
            ChatCompletion.BuiltInToolType.WEB_SEARCH, null, List.of("a.com"), List.of("b.com")
        );
        assertThrows(IllegalArgumentException.class, tool::toSdkTool);
    }

    @EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".*")
    @Test
    void shouldUseWebSearchBuiltInTool() throws Exception {
        var runContext = runContextFactory.of(Map.of());
        var task = ChatCompletion.builder()
            .apiKey(Property.ofValue(ANTHROPIC_API_KEY))
            .model(Property.ofValue("claude-sonnet-4-6"))
            .maxTokens(Property.ofValue(2048L))
            .messages(
                Property.ofValue(
                    List.of(
                        ChatCompletion.ChatMessage.builder()
                            .type(ChatCompletion.ChatMessageType.USER)
                            .content("Search the web: what is Kestra? Answer in one sentence.")
                            .build()
                    )
                )
            )
            .tools(
                Property.ofValue(
                    List.of(
                        ChatCompletion.BuiltInTool.builder()
                            .type(ChatCompletion.BuiltInToolType.WEB_SEARCH)
                            .maxUses(1L)
                            .build()
                    )
                )
            )
            .build();

        var output = task.run(runContext);

        assertThat(output.getRawResponse(), containsString("web_search_tool_result"));
        assertThat(output.getOutputText(), not(emptyOrNullString()));
    }
}
