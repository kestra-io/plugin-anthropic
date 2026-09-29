package io.kestra.plugin.anthropic;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;

import jakarta.inject.Inject;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@KestraTest
public class CountTokensTest {

    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void countsInputTokensWithoutCallingTheModel() throws Exception {
        List<Captured> captured = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
            "/",
            exchange ->
            {
                byte[] requestBody = exchange.getRequestBody().readAllBytes();
                String path = exchange.getRequestURI().getPath();
                captured.add(
                    new Captured(
                        exchange.getRequestMethod(),
                        path,
                        new String(requestBody, StandardCharsets.UTF_8)
                    )
                );

                byte[] responseBody;
                int status;
                if ("/v1/messages/count_tokens".equals(path)) {
                    status = 200;
                    responseBody = "{\"input_tokens\":42}".getBytes(StandardCharsets.UTF_8);
                } else {
                    status = 404;
                    responseBody = ("{\"error\":{\"message\":\"unexpected " + path + "\"}}")
                        .getBytes(StandardCharsets.UTF_8);
                }
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, responseBody.length);
                exchange.getResponseBody().write(responseBody);
                exchange.close();
            }
        );
        server.start();

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

            var task = StubbedCountTokens.builder()
                .apiKey(Property.ofExpression("{{ apiKey }}"))
                .model(Property.ofExpression("{{ model }}"))
                .system(Property.ofExpression("{{ system }}"))
                .messages(Property.ofExpression("{{ messages }}"))
                .tools(Property.ofExpression("{{ tools }}"))
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .build();

            var output = task.run(runContext);

            assertThat(output, notNullValue());
            assertThat(output.getInputTokens(), is(42L));
            assertThat(captured, hasSize(1));
            assertThat(captured.get(0).method(), is("POST"));
            assertThat(captured.get(0).path(), is("/v1/messages/count_tokens"));
            assertThat(captured.get(0).body(), containsString("claude-sonnet-4-6"));
            assertThat(captured.get(0).body(), containsString("What is the capital of Japan?"));
            assertThat(captured.get(0).body(), containsString("Tokyo."));
            assertThat(captured.get(0).body(), containsString("Be brief."));
            assertThat(captured.get(0).body(), containsString("extract_person"));
            assertThat(captured.get(0).body(), not(containsString("max_tokens")));
        } finally {
            server.stop(0);
        }
    }

    private record Captured(String method, String path, String body) {
    }

    @SuperBuilder
    @NoArgsConstructor
    static class StubbedCountTokens extends CountTokens {
        private String baseUrl;

        @Override
        protected AnthropicClient buildClient(String rApiKey) {
            return AnthropicOkHttpClient.builder()
                .apiKey(rApiKey)
                .baseUrl(baseUrl)
                .maxRetries(0)
                .build();
        }
    }
}
