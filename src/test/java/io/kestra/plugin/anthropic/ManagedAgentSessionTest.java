package io.kestra.plugin.anthropic;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;

import jakarta.inject.Inject;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class ManagedAgentSessionTest {

    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void shouldRequireApiKey() {
        var task = ManagedAgentSession.builder()
            .id("session")
            .type(ManagedAgentSession.class.getName())
            .apiKey(Property.ofValue(" "))
            .model(Property.ofValue("claude-sonnet-5"))
            .prompt(Property.ofValue("Do something"))
            .build();

        var error = assertThrows(IllegalArgumentException.class, () -> task.run(runContextFactory.of(Map.of())));
        assertThat(error.getMessage(), containsString("apiKey is required"));
    }

    @Test
    void shouldRequirePrompt() {
        var task = ManagedAgentSession.builder()
            .id("session")
            .type(ManagedAgentSession.class.getName())
            .apiKey(Property.ofValue("test-key"))
            .model(Property.ofValue("claude-sonnet-5"))
            .prompt(Property.ofValue(" "))
            .build();

        var error = assertThrows(IllegalArgumentException.class, () -> task.run(runContextFactory.of(Map.of())));
        assertThat(error.getMessage(), containsString("prompt is required"));
    }
}
