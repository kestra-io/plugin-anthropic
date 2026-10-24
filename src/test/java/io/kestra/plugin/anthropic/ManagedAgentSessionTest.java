package io.kestra.plugin.anthropic;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.sun.net.httpserver.HttpServer;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.utils.IdUtils;

import jakarta.inject.Inject;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class ManagedAgentSessionTest {

    private static final Pattern EVENT_TYPE = Pattern.compile("\"type\":\"([a-z_.]+)\"");

    private static final String AGENT_MESSAGE = "{\"type\":\"agent.message\",\"id\":\"evt_1\",\"processed_at\":\"2026-10-22T10:00:00Z\",\"content\":[{\"type\":\"text\",\"text\":\"All TODOs summarized.\"}]}";

    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void shouldRunSessionToCompletion() throws Exception {
        var mock = MockApi.start(sse(AGENT_MESSAGE, idle("end_turn")));
        try {
            var output = task(mock.baseUrl())
                .repository(
                    ManagedAgentSession.GitRepository.builder()
                        .url(Property.ofValue("https://github.com/acme/app"))
                        .branch(Property.ofValue("main"))
                        .token(Property.ofValue("gh-token"))
                        .build()
                )
                .mcpServers(
                    Property.ofValue(
                        List.of(
                            ManagedAgentSession.McpServer.builder()
                                .name(Property.ofValue("docs"))
                                .url(Property.ofValue("https://mcp.example.com/sse"))
                                .build()
                        )
                    )
                )
                .maxCostUsd(Property.ofValue(new BigDecimal("2.5")))
                .build()
                .run(runContextFactory.of(Map.of()));

            assertThat(output.getSessionId(), is("sesn_1"));
            assertThat(output.getStatus(), is(ClaudeCodeSessionStatus.FINISHED));
            assertThat(output.getMessage(), is("All TODOs summarized."));
            assertThat(output.getCostUsd(), comparesEqualTo(new BigDecimal("0.42")));

            assertThat(mock.requests(), hasItem("POST /v1/agents"));
            assertThat(mock.requests(), hasItem("POST /v1/environments"));
            assertThat(mock.requests(), hasItem("POST /v1/sessions"));
            assertThat(mock.requests(), hasItem("POST /v1/sessions/sesn_1/events"));
            assertThat(mock.requests(), hasItem("POST /v1/agents/agent_1/archive"));
            assertThat(mock.requests(), hasItem("POST /v1/environments/env_1/archive"));
            assertThat(mock.bodyOf("POST /v1/agents"), containsString("claude-sonnet-5"));
            assertThat(mock.bodyOf("POST /v1/agents"), containsString("mcp_toolset"));
            assertThat(mock.bodyOf("POST /v1/sessions"), containsString("github_repository"));
            assertThat(mock.bodyOf("POST /v1/sessions"), containsString("2.5"));
            assertThat(mock.bodyOf("POST /v1/sessions/sesn_1/events"), containsString("user.message"));
            assertThat(mock.bodyOf("POST /v1/environments"), containsString("unrestricted"));
        } finally {
            mock.stop();
        }
    }

    @Test
    void shouldReportSpendingLimit() throws Exception {
        var mock = MockApi.start(sse(AGENT_MESSAGE, idle("budget_reached")));
        try {
            var output = task(mock.baseUrl()).build().run(runContextFactory.of(Map.of()));

            assertThat(output.getStatus(), is(ClaudeCodeSessionStatus.SPENDING_LIMIT_REACHED));
        } finally {
            mock.stop();
        }
    }

    @Test
    void shouldReportWaitingForInput() throws Exception {
        var mock = MockApi.start(
            sse(
                AGENT_MESSAGE,
                "{\"type\":\"session.status_idle\",\"id\":\"evt_2\",\"processed_at\":\"2026-10-22T10:00:01Z\",\"stop_reason\":{\"type\":\"requires_action\",\"event_ids\":[\"evt_1\"]}}"
            )
        );
        try {
            var output = task(mock.baseUrl()).build().run(runContextFactory.of(Map.of()));

            assertThat(output.getStatus(), is(ClaudeCodeSessionStatus.WAITING_FOR_INPUT));
        } finally {
            mock.stop();
        }
    }

    @Test
    void shouldFailWhenSessionIsTerminated() throws Exception {
        var mock = MockApi.start(sse("{\"type\":\"session.status_terminated\",\"id\":\"evt_3\",\"processed_at\":\"2026-10-22T10:00:01Z\"}"));
        try {
            var failure = assertThrows(IllegalStateException.class, () -> task(mock.baseUrl()).build().run(runContextFactory.of(Map.of())));

            assertThat(failure.getMessage(), containsString("terminated"));
            assertThat(mock.requests(), hasItem("POST /v1/agents/agent_1/archive"));
        } finally {
            mock.stop();
        }
    }

    @Test
    void shouldInterruptSessionWhenStreamEndsWithoutTerminalEvent() throws Exception {
        var mock = MockApi.start(sse(AGENT_MESSAGE));
        try {
            var failure = assertThrows(IllegalStateException.class, () -> task(mock.baseUrl()).build().run(runContextFactory.of(Map.of())));

            assertThat(failure.getMessage(), containsString("closed before the session stopped"));
            var eventPosts = mock.requests().stream().filter("POST /v1/sessions/sesn_1/events"::equals).count();
            assertThat(eventPosts, is(2L)); // the prompt, then the interrupt
        } finally {
            mock.stop();
        }
    }

    @Test
    void shouldStopSessionWhenTaskIsInterruptedWhileAgentIsSilent() throws Exception {
        var mock = MockApi.start("", true);
        try {
            var failure = new AtomicReference<Throwable>();
            var worker = new Thread(() ->
            {
                try {
                    task(mock.baseUrl()).build().run(runContextFactory.of(Map.of()));
                } catch (Throwable e) {
                    failure.set(e);
                }
            });
            worker.start();
            await(() -> mock.requests().contains("POST /v1/sessions/sesn_1/events"));

            // what the worker does when the task timeout expires
            worker.interrupt();
            worker.join(TimeUnit.SECONDS.toMillis(10));

            assertThat(worker.isAlive(), is(false));
            assertThat(failure.get(), notNullValue());
            var eventPosts = mock.requests().stream().filter("POST /v1/sessions/sesn_1/events"::equals).count();
            assertThat(eventPosts, is(2L)); // the prompt, then the interrupt
            assertThat(mock.requests(), hasItem("POST /v1/agents/agent_1/archive"));
            assertThat(mock.requests(), hasItem("POST /v1/environments/env_1/archive"));
        } finally {
            mock.stop();
        }
    }

    @Test
    void shouldCreateLimitedEnvironment() throws Exception {
        var mock = MockApi.start(sse(AGENT_MESSAGE, idle("end_turn")));
        try {
            task(mock.baseUrl())
                .networking(Property.ofValue(ManagedAgentSession.Networking.LIMITED))
                .allowedHosts(Property.ofValue(List.of("github.com")))
                .build()
                .run(runContextFactory.of(Map.of()));

            assertThat(mock.bodyOf("POST /v1/environments"), containsString("limited"));
            assertThat(mock.bodyOf("POST /v1/environments"), containsString("github.com"));
        } finally {
            mock.stop();
        }
    }

    @Test
    void shouldRequireApiKey() {
        var task = ManagedAgentSession.builder()
            .id("session_" + IdUtils.create())
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
            .id("session_" + IdUtils.create())
            .type(ManagedAgentSession.class.getName())
            .apiKey(Property.ofValue("test-key"))
            .model(Property.ofValue("claude-sonnet-5"))
            .prompt(Property.ofValue(" "))
            .build();

        var error = assertThrows(IllegalArgumentException.class, () -> task.run(runContextFactory.of(Map.of())));
        assertThat(error.getMessage(), containsString("prompt is required"));
    }

    @Test
    void shouldRejectNonPositiveSpendingLimit() {
        var task = ManagedAgentSession.builder()
            .id("session_" + IdUtils.create())
            .type(ManagedAgentSession.class.getName())
            .apiKey(Property.ofValue("test-key"))
            .model(Property.ofValue("claude-sonnet-5"))
            .prompt(Property.ofValue("Do something"))
            .maxCostUsd(Property.ofValue(BigDecimal.ZERO))
            .build();

        var error = assertThrows(IllegalArgumentException.class, () -> task.run(runContextFactory.of(Map.of())));
        assertThat(error.getMessage(), containsString("maxCostUsd must be greater than 0"));
    }

    @Test
    void shouldRejectMcpServerWithoutName() throws Exception {
        var mock = MockApi.start(sse(idle("end_turn")));
        try {
            var task = task(mock.baseUrl())
                .mcpServers(
                    Property.ofValue(
                        List.of(
                            ManagedAgentSession.McpServer.builder()
                                .name(Property.ofValue(""))
                                .url(Property.ofValue("https://mcp.example.com/sse"))
                                .build()
                        )
                    )
                )
                .build();

            var error = assertThrows(IllegalArgumentException.class, () -> task.run(runContextFactory.of(Map.of())));
            assertThat(error.getMessage(), containsString("mcpServers[].name is required"));
        } finally {
            mock.stop();
        }
    }

    private static LocalManagedAgentSession.LocalManagedAgentSessionBuilder<?, ?> task(String baseUrl) {
        return LocalManagedAgentSession.builder()
            .id("session_" + IdUtils.create())
            .type(ManagedAgentSession.class.getName())
            .baseUrl(baseUrl)
            .apiKey(Property.ofValue("test-key"))
            .model(Property.ofValue("claude-sonnet-5"))
            .prompt(Property.ofValue("Summarize the open TODOs."));
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(25);
        }
    }

    private static String idle(String stopReason) {
        return "{\"type\":\"session.status_idle\",\"id\":\"evt_2\",\"processed_at\":\"2026-10-22T10:00:01Z\",\"stop_reason\":{\"type\":\"" + stopReason + "\"}}";
    }

    private static String sse(String... events) {
        var body = new StringBuilder();
        for (var event : events) {
            // the SDK only accepts messages that name their type in an `event:` line, as the real API does
            var matcher = EVENT_TYPE.matcher(event);
            matcher.find();
            body.append("event: ").append(matcher.group(1)).append("\n");
            body.append("data: ").append(event).append("\n\n");
        }
        return body.toString();
    }

    @SuperBuilder
    @NoArgsConstructor
    public static final class LocalManagedAgentSession extends ManagedAgentSession {
        private String baseUrl;

        @Override
        protected AnthropicClient anthropicClient(String rApiKey) {
            return AnthropicOkHttpClient.builder()
                .apiKey(rApiKey)
                .baseUrl(baseUrl)
                .maxRetries(0)
                .build();
        }
    }

    /** Minimal Managed Agents API served from memory; records "METHOD /path" and the request bodies. */
    private static final class MockApi {
        private final HttpServer server;
        private final List<String> requests = Collections.synchronizedList(new ArrayList<>());
        private final Map<String, String> bodies = new ConcurrentHashMap<>();
        private final CountDownLatch release = new CountDownLatch(1);
        private final ExecutorService executor = Executors.newCachedThreadPool();

        private MockApi(HttpServer server) {
            this.server = server;
        }

        static MockApi start(String streamBody) throws Exception {
            return start(streamBody, false);
        }

        /** With hangStream, the event stream stays open and silent, like an agent running a long command. */
        static MockApi start(String streamBody, boolean hangStream) throws Exception {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            var mock = new MockApi(server);
            server.setExecutor(mock.executor);
            server.createContext("/", exchange ->
            {
                var key = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
                var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                mock.requests.add(key);
                mock.bodies.merge(key, body, (previous, next) -> previous + "\n" + next);

                if (hangStream && "GET /v1/sessions/sesn_1/events/stream".equals(key)) {
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().flush();
                    try {
                        mock.release.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    exchange.close();
                    return;
                }

                var contentType = "application/json";
                var response = switch (key) {
                    case "POST /v1/agents" -> "{\"id\":\"agent_1\",\"type\":\"agent\"}";
                    case "POST /v1/environments" -> "{\"id\":\"env_1\",\"type\":\"environment\"}";
                    case "POST /v1/sessions" -> "{\"id\":\"sesn_1\",\"type\":\"session\"}";
                    case "POST /v1/sessions/sesn_1/events" -> "{\"data\":[]}";
                    case "GET /v1/sessions/sesn_1/events/stream" -> {
                        contentType = "text/event-stream";
                        yield streamBody;
                    }
                    case "GET /v1/sessions/sesn_1" ->
                        "{\"id\":\"sesn_1\",\"type\":\"session\",\"status\":\"idle\",\"usage\":{\"input_tokens\":10,\"output_tokens\":5,\"cache_read_input_tokens\":2,\"list_cost\":{\"amount\":\"0.42\",\"currency\":\"USD\"}}}";
                    case "POST /v1/agents/agent_1/archive" -> "{\"id\":\"agent_1\",\"type\":\"agent\"}";
                    case "POST /v1/environments/env_1/archive" -> "{\"id\":\"env_1\",\"type\":\"environment\"}";
                    default -> null;
                };

                var bytes = (response == null ? "{\"error\":{\"message\":\"unexpected " + key + "\"}}" : response).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", contentType);
                exchange.sendResponseHeaders(response == null ? 404 : 200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            return mock;
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        List<String> requests() {
            return requests;
        }

        String bodyOf(String key) {
            return bodies.getOrDefault(key, "");
        }

        void stop() {
            release.countDown();
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
