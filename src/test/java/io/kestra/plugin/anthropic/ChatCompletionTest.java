package io.kestra.plugin.anthropic;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@KestraTest
public class ChatCompletionTest {

    private final String ANTHROPIC_API_KEY = System.getenv("ANTHROPIC_API_KEY");

    @Inject
    private RunContextFactory runContextFactory;

    @EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".*")
    @Test
    void shouldGetResultsWithAnthropicChatCompletion() throws Exception {
        var runContext = runContextFactory.of(Map.of(
            "apiKey", ANTHROPIC_API_KEY,
            "model", "claude-3-5-sonnet-20241022",
            "messages", List.of(
                ChatCompletion.ChatMessage.builder()
                    .type(ChatCompletion.ChatMessageType.USER)
                    .content("What is the capital of France? Answer just the name.")
                    .build()
            )
        ));

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
        schema.put("properties", Map.of(
            "name", Map.of("type", "string"),
            "age", Map.of("type", "integer")
        ));
        schema.put("required", List.of("name", "age"));

        var tool = ChatCompletion.Tool.builder()
            .name("extract_person")
            .description("Extract person info")
            .inputSchema(schema)
            .build();

        var runContext = runContextFactory.of(Map.of(
            "apiKey", ANTHROPIC_API_KEY,
            "model", "claude-3-5-sonnet-20241022",
            "messages", List.of(
                ChatCompletion.ChatMessage.builder()
                    .type(ChatCompletion.ChatMessageType.USER)
                    .content("John is 25 years old")
                    .build()
            ),
            "tools", List.of(tool)
        ));

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
}
