package io.kestra.plugin.anthropic;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;

import jakarta.inject.Inject;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@KestraTest
public class CountTokensTest {

    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void shouldCountInputTokens() throws Exception {
        List<String> paths = new ArrayList<>();
        List<String> bodies = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
            "/",
            exchange ->
            {
                String path = exchange.getRequestURI().getPath();
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
        CountTokens.baseUrlOverride.set("http://127.0.0.1:" + server.getAddress().getPort());

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

            var task = CountTokens.builder()
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
            assertThat(paths.get(0), is("/v1/messages/count_tokens"));
            assertThat(bodies.get(0), containsString("claude-sonnet-4-6"));
            assertThat(bodies.get(0), containsString("What is the capital of Japan?"));
            assertThat(bodies.get(0), containsString("Tokyo."));
            assertThat(bodies.get(0), containsString("Be brief."));
            assertThat(bodies.get(0), containsString("extract_person"));
            assertThat(bodies.get(0), not(containsString("max_tokens")));
        } finally {
            CountTokens.baseUrlOverride.remove();
            server.stop(0);
        }
    }
}
