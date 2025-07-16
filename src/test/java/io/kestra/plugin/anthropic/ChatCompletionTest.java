package io.kestra.plugin.anthropic;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.hamcrest.Matchers.notNullValue;

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
}
