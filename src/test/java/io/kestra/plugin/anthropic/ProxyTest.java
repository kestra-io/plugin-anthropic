package io.kestra.plugin.anthropic;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import io.kestra.core.http.client.configurations.ProxyConfiguration;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;

import jakarta.inject.Inject;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class ProxyTest {
    private static final String USERNAME = "proxy-user";
    private static final String PASSWORD = "proxy-secret";
    // the host never resolves, so only a request routed through the proxy can succeed
    private static final String UNREACHABLE_BASE_URL = "http://anthropic.invalid";

    @Inject
    private RunContextFactory runContextFactory;

    private HttpServer server;
    private final List<Integer> statuses = new CopyOnWriteArrayList<>();
    private final List<String> bodies = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startProxy() throws Exception {
        var expected = "Basic " + Base64.getEncoder().encodeToString((USERNAME + ":" + PASSWORD).getBytes(StandardCharsets.ISO_8859_1));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
            "/",
            exchange ->
            {
                var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                var path = exchange.getRequestURI().getPath();
                int status;
                String response;
                if (!expected.equals(exchange.getRequestHeaders().getFirst("Proxy-Authorization"))) {
                    status = 407;
                    response = "{}";
                    exchange.getResponseHeaders().set("Proxy-Authenticate", "Basic realm=\"proxy\"");
                } else if ("/v1/models".equals(path)) {
                    status = 200;
                    response = "{\"data\":[{\"type\":\"model\",\"id\":\"claude-sonnet-4-6\",\"display_name\":\"Claude Sonnet\",\"created_at\":\"2025-01-01T00:00:00Z\"}],\"has_more\":false,\"first_id\":\"claude-sonnet-4-6\",\"last_id\":\"claude-sonnet-4-6\"}";
                } else if ("/v1/messages".equals(path)) {
                    status = 200;
                    response = "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-sonnet-4-6\",\"content\":[{\"type\":\"text\",\"text\":\"Tokyo\"}],\"stop_reason\":\"end_turn\",\"stop_sequence\":null,\"usage\":{\"input_tokens\":3,\"output_tokens\":1}}";
                } else {
                    status = 404;
                    response = "{\"error\":{\"message\":\"unexpected " + path + "\"}}";
                }
                statuses.add(status);
                bodies.add(body);
                var bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            }
        );
        server.start();
    }

    @AfterEach
    void stopProxy() {
        server.stop(0);
    }

    @Test
    void shouldListModelsThroughAuthenticatedProxy() throws Exception {
        var task = ListModels.builder()
            .apiKey(Property.ofValue("test-key"))
            .baseUrl(Property.ofValue(UNREACHABLE_BASE_URL))
            .proxy(proxy(Proxy.Type.HTTP, PASSWORD))
            .build();

        var output = task.run(runContextFactory.of());

        assertThat(output.getModels(), hasSize(1));
        assertThat(output.getModels().getFirst().getId(), is("claude-sonnet-4-6"));
        assertThat(statuses, contains(407, 200));
    }

    @Test
    void shouldChatThroughAuthenticatedProxy() throws Exception {
        var task = chat(proxy(Proxy.Type.HTTP, PASSWORD));

        var output = task.run(runContextFactory.of());

        assertThat(output.getOutputText(), is("Tokyo"));
        assertThat(statuses, contains(407, 200));
        assertThat(bodies.getLast(), containsString("Capital of Japan?"));
    }

    @Test
    void shouldUseHttpProxyWhenTypeIsOmitted() throws Exception {
        var task = chat(proxy(null, PASSWORD));

        var output = task.run(runContextFactory.of());

        assertThat(output.getOutputText(), is("Tokyo"));
        assertThat(statuses, contains(407, 200));
    }

    @Test
    void shouldFailWithoutLoopingOnWrongPassword() {
        var task = chat(proxy(Proxy.Type.HTTP, "wrong"));

        var failure = assertThrows(Exception.class, () -> task.run(runContextFactory.of()));

        assertThat(failure.getMessage(), containsString("407"));
        assertThat(statuses, contains(407, 407));
    }

    @Test
    void shouldRejectSocksWithCredentials() {
        var task = chat(proxy(Proxy.Type.SOCKS, PASSWORD));

        var failure = assertThrows(IllegalArgumentException.class, () -> task.run(runContextFactory.of()));

        assertThat(failure.getMessage(), containsString("SOCKS"));
        assertThat(failure.getMessage(), not(containsString(PASSWORD)));
        assertThat(statuses, empty());
    }

    @Test
    void shouldRejectAddressWithoutPort() {
        var task = chat(
            ProxyConfiguration.builder()
                .type(Property.ofValue(Proxy.Type.HTTP))
                .address(Property.ofValue("127.0.0.1"))
                .build()
        );

        var failure = assertThrows(IllegalArgumentException.class, () -> task.run(runContextFactory.of()));

        assertThat(failure.getMessage(), is("A proxy port is required when a proxy address is set (proxy.port)."));
    }

    @Test
    void shouldRejectOutOfRangePort() {
        var task = chat(
            ProxyConfiguration.builder()
                .address(Property.ofValue("127.0.0.1"))
                .port(Property.ofValue(70000))
                .build()
        );

        var failure = assertThrows(IllegalArgumentException.class, () -> task.run(runContextFactory.of()));

        assertThat(failure.getMessage(), containsString("proxy.port"));
    }

    @Test
    void shouldIgnoreCredentialsWithoutAddress() {
        var task = chat(
            ProxyConfiguration.builder()
                .username(Property.ofValue(USERNAME))
                .password(Property.ofValue(PASSWORD))
                .build()
        );

        var failure = assertThrows(Exception.class, () -> task.run(runContextFactory.of()));

        assertThat(failure.getMessage(), not(containsString(PASSWORD)));
        assertThat(statuses, empty());
    }

    @Test
    void shouldRenderProxyPropertiesFromExpressions() throws Exception {
        var task = chat(
            ProxyConfiguration.builder()
                .address(Property.ofExpression("{{ proxyHost }}"))
                .port(Property.ofExpression("{{ proxyPort }}"))
                .username(Property.ofValue(USERNAME))
                .password(Property.ofExpression("{{ proxyPassword }}"))
                .build()
        );

        var runContext = runContextFactory.of(
            Map.of("proxyHost", "127.0.0.1", "proxyPort", server.getAddress().getPort(), "proxyPassword", PASSWORD)
        );
        var output = task.run(runContext);

        assertThat(output.getOutputText(), is("Tokyo"));
        assertThat(statuses, contains(407, 200));
    }

    private ChatCompletion chat(ProxyConfiguration proxy) {
        return ChatCompletion.builder()
            .apiKey(Property.ofValue("test-key"))
            .baseUrl(Property.ofValue(UNREACHABLE_BASE_URL))
            .model(Property.ofValue("claude-sonnet-4-6"))
            .messages(
                Property.ofValue(
                    List.of(
                        ChatCompletion.ChatMessage.builder()
                            .type(ChatCompletion.ChatMessageType.USER)
                            .content("Capital of Japan?")
                            .build()
                    )
                )
            )
            .proxy(proxy)
            .build();
    }

    private ProxyConfiguration proxy(Proxy.Type type, String password) {
        var builder = ProxyConfiguration.builder()
            .address(Property.ofValue("127.0.0.1"))
            .port(Property.ofValue(server.getAddress().getPort()))
            .username(Property.ofValue(USERNAME))
            .password(Property.ofValue(password));
        if (type != null) {
            builder.type(Property.ofValue(type));
        }
        return builder.build();
    }
}
