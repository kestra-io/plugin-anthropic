package io.kestra.plugin.anthropic;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.sun.net.httpserver.HttpServer;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;

import jakarta.inject.Inject;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
public class CountTokensTest {

    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void shouldCountInputTokens() throws Exception {
        List<String> methods = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        List<String> bodies = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
            "/",
            exchange ->
            {
                String path = exchange.getRequestURI().getPath();
                methods.add(exchange.getRequestMethod());
                paths.add(path);
                bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

                byte[] responseBody;
                int status;
                if ("/v1/messages/count_tokens".equals(path)) {
                    status = 200;
                    responseBody = "{\"input_tokens\":42}".getBytes(StandardCharsets.UTF_8);
                } else {
                    status = 404;
                    responseBody = ("{\"error\":{\"message\":\"unexpected " + path + "\"}}").getBytes(StandardCharsets.UTF_8);
                }
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, responseBody.length);
                exchange.getResponseBody().write(responseBody);
                exchange.close();
            }
        );
        server.start();
        var baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

        try {
            Map<String, Object> schema = new HashMap<>();
            schema.put("type", "object");
            schema.put(
                "properties", Map.of(
                    "name", Map.of("type", "string"),
                    "age", Map.of("type", "integer")
                )
            );
            schema.put("required", List.of("name", "age"));

            var tool = CountTokens.Tool.builder()
                .name("extract_person")
                .description("Extract person info")
                .inputSchema(schema)
                .build();

            var runContext = runContextFactory.of(
                Map.of(
                    "apiKey", "test-key",
                    "model", "claude-sonnet-4-6",
                    "system", "Be brief.",
                    "messages", List.of(
                        CountTokens.ChatMessage.builder()
                            .type(CountTokens.ChatMessageType.USER)
                            .content("What is the capital of Japan?")
                            .build(),
                        CountTokens.ChatMessage.builder()
                            .type(CountTokens.ChatMessageType.ASSISTANT)
                            .content("Tokyo.")
                            .build()
                    ),
                    "tools", List.of(tool)
                )
            );

            var task = LocalCountTokens.builder()
                .baseUrl(baseUrl)
                .apiKey(Property.ofExpression("{{ apiKey }}"))
                .model(Property.ofExpression("{{ model }}"))
                .system(Property.ofExpression("{{ system }}"))
                .messages(Property.ofExpression("{{ messages }}"))
                .tools(Property.ofExpression("{{ tools }}"))
                .build();

            var output = task.run(runContext);

            assertThat(output, notNullValue());
            assertThat(output.getInputTokens(), is(42L));
            assertThat(paths, hasSize(1));
            assertThat(methods.get(0), is("POST"));
            assertThat(paths.get(0), is("/v1/messages/count_tokens"));
            assertThat(bodies.get(0), containsString("claude-sonnet-4-6"));
            assertThat(bodies.get(0), containsString("What is the capital of Japan?"));
            assertThat(bodies.get(0), containsString("Tokyo."));
            assertThat(bodies.get(0), containsString("Be brief."));
            assertThat(bodies.get(0), containsString("extract_person"));
            assertThat(bodies.get(0), not(containsString("max_tokens")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldRejectMissingApiKey() {
        var task = CountTokens.builder()
            .model(Property.ofValue("claude-sonnet-4-6"))
            .messages(oneMessage())
            .build();

        var failure = assertThrows(IllegalArgumentException.class, () -> task.run(runContextFactory.of()));

        assertThat(failure.getMessage(), containsString("apiKey"));
    }

    @Test
    void shouldRejectBlankModel() {
        var task = CountTokens.builder()
            .apiKey(Property.ofValue("test-key"))
            .model(Property.ofValue(""))
            .messages(oneMessage())
            .build();

        var failure = assertThrows(IllegalArgumentException.class, () -> task.run(runContextFactory.of()));

        assertThat(failure.getMessage(), containsString("model"));
    }

    @Test
    void shouldRejectMissingMessages() {
        var task = CountTokens.builder()
            .apiKey(Property.ofValue("test-key"))
            .model(Property.ofValue("claude-sonnet-4-6"))
            .build();

        var failure = assertThrows(IllegalArgumentException.class, () -> task.run(runContextFactory.of()));

        assertThat(failure.getMessage(), containsString("messages"));
    }

    private Property<List<CountTokens.ChatMessage>> oneMessage() {
        return Property.ofValue(
            List.of(
                CountTokens.ChatMessage.builder()
                    .type(CountTokens.ChatMessageType.USER)
                    .content("Hi")
                    .build()
            )
        );
    }

    @SuperBuilder
    @NoArgsConstructor
    public static final class LocalCountTokens extends CountTokens {
        private String baseUrl;

        @Override
        protected AnthropicClient anthropicClient(String apiKey) {
            return AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .maxRetries(0)
                .build();
        }
    }
}
