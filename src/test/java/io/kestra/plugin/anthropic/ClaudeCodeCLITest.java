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
class ClaudeCodeCLITest {

    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void shouldRequireApiKey() {
        var task = ClaudeCodeCLI.builder()
            .id("cli")
            .type(ClaudeCodeCLI.class.getName())
            .apiKey(Property.ofValue(" "))
            .prompt(Property.ofValue("Do something"))
            .build();

        var error = assertThrows(IllegalArgumentException.class, () -> task.run(runContextFactory.of(Map.of())));
        assertThat(error.getMessage(), containsString("apiKey is required"));
    }

    @Test
    void shouldRequirePrompt() {
        var task = ClaudeCodeCLI.builder()
            .id("cli")
            .type(ClaudeCodeCLI.class.getName())
            .apiKey(Property.ofValue("test-key"))
            .prompt(Property.ofValue(" "))
            .build();

        var error = assertThrows(IllegalArgumentException.class, () -> task.run(runContextFactory.of(Map.of())));
        assertThat(error.getMessage(), containsString("prompt is required"));
    }
}
