package io.kestra.plugin.anthropic;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.serializers.JacksonMapper;

import jakarta.inject.Inject;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

@KestraTest
class ChatCompletionCapabilitiesTest {
    @Inject
    private RunContextFactory runContextFactory;

    private HttpServer server;
    private String baseUrl;
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private final List<String> betaHeaders = new CopyOnWriteArrayList<>();
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private String response;
    private String contentType;
    private int status;
    private CountDownLatch streamRelease;

    @BeforeEach
    void startServer() throws Exception {
        requests.clear();
        betaHeaders.clear();
        paths.clear();
        response = message("[{\"type\":\"text\",\"text\":\"Hello\",\"citations\":null}]");
        contentType = "application/json";
        status = 200;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange ->
        {
            requests.add(JacksonMapper.ofJson().readTree(exchange.getRequestBody()));
            paths.add(exchange.getRequestURI().getPath());
            betaHeaders.add(String.valueOf(exchange.getRequestHeaders().getFirst("anthropic-beta")));
            var bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(status, streamRelease == null ? bytes.length : 0);
            exchange.getResponseBody().write(bytes);
            exchange.getResponseBody().flush();
            if (streamRelease != null) {
                try {
                    streamRelease.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void shouldPreservePlainTextDefaults() throws Exception {
        var output = task().build().run(runContextFactory.of());
        var request = requests.getFirst();
        assertThat(paths, contains("/v1/messages"));
        assertThat(request.path("messages").path(0).path("content").asText(), is("Hello"));
        assertThat(request.path("max_tokens").asLong(), is(1024L));
        assertThat(request.has("thinking"), is(false));
        assertThat(request.has("temperature"), is(false));
        assertThat(request.path("stream").asBoolean(), is(false));
        assertThat(output.getOutputText(), is("Hello"));
        assertThat(output.getStopReason(), is("end_turn"));
        assertThat(output.getThinking(), nullValue());
        assertThat(output.getCitations(), nullValue());
    }

    @Test
    void shouldRenderThinkingAndAttributionAndExtractOutputs() throws Exception {
        response = message("""
            [{"type":"thinking","thinking":"Consider this.","signature":"sig"},
             {"type":"redacted_thinking","data":"hidden"},
             {"type":"text","text":"Answer","citations":[
               {"type":"page_location","cited_text":"Evidence","document_index":0,"document_title":"Report","start_page_number":1,"end_page_number":2},
               {"type":"char_location","cited_text":"Quote","document_index":1,"document_title":null,"start_char_index":0,"end_char_index":5},
               {"type":"content_block_location","cited_text":"Block","document_index":2,"document_title":"Blocks","start_block_index":0,"end_block_index":1}
             ]}]
            """).replace("\"stop_reason\":\"end_turn\",\"stop_sequence\":null", "\"stop_reason\":\"stop_sequence\",\"stop_sequence\":\"END\"");
        var output = task()
            .thinking(Property.ofExpression("{{ enabled }}"))
            .budgetTokens(Property.ofExpression("{{ budget }}"))
            .maxTokens(Property.ofValue(4096L))
            .temperature(Property.ofValue(0.2))
            .stopSequences(Property.ofExpression("{{ stops }}"))
            .metadata(dynamic(new ChatCompletion.Metadata("{{ user }}")))
            .serviceTier(Property.ofExpression("{{ tier }}"))
            .betas(Property.ofExpression("{{ betas }}"))
            .build().run(
                runContextFactory.of(
                    Map.of(
                        "enabled", true, "budget", 1024, "stops", List.of("END"), "user", "workflow-42",
                        "tier", "STANDARD_ONLY", "betas", List.of("files-api-2025-04-14", "future-feature")
                    )
                )
            );
        var request = requests.getFirst();
        assertThat(request.path("thinking").path("type").asText(), is("enabled"));
        assertThat(request.path("thinking").path("budget_tokens").asInt(), is(1024));
        assertThat(request.path("temperature").asDouble(), is(1.0));
        assertThat(request.path("stop_sequences").path(0).asText(), is("END"));
        assertThat(request.path("metadata").path("user_id").asText(), is("workflow-42"));
        assertThat(request.path("service_tier").asText(), is("standard_only"));
        assertThat(betaHeaders, contains("files-api-2025-04-14,future-feature"));
        assertThat(output.getOutputText(), is("Answer"));
        assertThat(output.getThinking(), is("Consider this."));
        assertThat(output.getRawResponse(), containsString("redacted_thinking"));
        assertThat(output.getStopSequence(), is("END"));
        assertThat(output.getCitations(), hasSize(3));
        assertThat(output.getCitations().getFirst().startPageNumber(), is(1L));
        assertThat(output.getCitations().get(1).endCharIndex(), is(5L));
        assertThat(output.getCitations().get(2).endBlockIndex(), is(1L));
    }

    @Test
    void shouldSendEveryImageAndDocumentSourceAndGranularCacheControls() throws Exception {
        var blocks = List.of(
            Map.of("type", "text", "text", "Inspect {{ subject }}"),
            Map.of("type", "image", "source", Map.of("type", "url", "url", "https://example.com/image.png")),
            Map.of("type", "image", "source", Map.of("type", "base64", "media_type", "image/png", "data", "aW1hZ2U=")),
            Map.of("type", "image", "source", Map.of("type", "file", "file_id", "file_image")),
            Map.of("type", "document", "source", Map.of("type", "url", "url", "https://example.com/report.pdf")),
            Map.of("type", "document", "source", Map.of("type", "base64", "mediaType", "application/pdf", "data", "cGRm")),
            Map.of("type", "document", "source", Map.of("type", "file", "fileId", "{{ file }}"), "title", "Report", "citations", Map.of("enabled", true))
        );
        task().messages(dynamic(List.of(new ChatCompletion.ChatMessage(ChatCompletion.ChatMessageType.USER, blocks, ChatCompletion.CacheControl.EPHEMERAL))))
            .systemPromptBlocks(dynamic(List.of(new ChatCompletion.SystemPromptBlock("Use {{ subject }}", ChatCompletion.CacheControl.EPHEMERAL))))
            .tools(Property.ofValue(List.of(new ChatCompletion.Tool("extract", "Extract", Map.of("type", "object"), ChatCompletion.CacheControl.EPHEMERAL))))
            .promptCaching(Property.ofValue(true))
            .build().run(runContextFactory.of(Map.of("subject", "documents", "file", "file_report")));
        var request = requests.getFirst();
        var content = request.path("messages").path(0).path("content");
        assertThat(content.size(), is(7));
        assertThat(content.path(0).path("text").asText(), is("Inspect documents"));
        assertThat(content.get(1).path("source").path("url").asText(), is("https://example.com/image.png"));
        assertThat(content.get(2).path("source").path("media_type").asText(), is("image/png"));
        assertThat(content.get(3).path("source").path("file_id").asText(), is("file_image"));
        assertThat(content.get(4).path("source").path("type").asText(), is("url"));
        assertThat(content.get(5).path("source").path("media_type").asText(), is("application/pdf"));
        assertThat(content.get(6).path("source").path("file_id").asText(), is("file_report"));
        assertThat(content.get(6).path("citations").path("enabled").asBoolean(), is(true));
        assertThat(content.get(6).path("cache_control").path("type").asText(), is("ephemeral"));
        assertThat(content.path(0).has("cache_control"), is(false));
        assertThat(request.path("system").path(0).path("text").asText(), is("Use documents"));
        assertThat(request.path("system").path(0).path("cache_control").path("type").asText(), is("ephemeral"));
        assertThat(request.path("tools").path(0).path("cache_control").path("type").asText(), is("ephemeral"));
        assertThat(request.path("cache_control").path("type").asText(), is("ephemeral"));
    }

    @Test
    void shouldCachePlainTextMessageAndIndividualBlock() throws Exception {
        task().system(Property.ofValue("Original string system"))
            .messages(
                Property.ofValue(
                    List.of(
                        new ChatCompletion.ChatMessage(ChatCompletion.ChatMessageType.USER, "Cached", ChatCompletion.CacheControl.EPHEMERAL),
                        new ChatCompletion.ChatMessage(
                            ChatCompletion.ChatMessageType.ASSISTANT,
                            List.of(ChatCompletion.ContentBlock.builder().type(ChatCompletion.ContentBlockType.TEXT).text("Reply").cacheControl(ChatCompletion.CacheControl.EPHEMERAL).build())
                        )
                    )
                )
            )
            .build().run(runContextFactory.of());
        var request = requests.getFirst();
        assertThat(request.path("system").asText(), is("Original string system"));
        for (var message : request.path("messages")) {
            assertThat(message.path("content").path(0).path("cache_control").path("type").asText(), is("ephemeral"));
        }
    }

    @Test
    void shouldSupportAllToolChoices() throws Exception {
        for (var choice : ChatCompletion.ToolChoice.values()) {
            task().toolChoice(Property.ofValue(choice)).toolChoiceName(Property.ofValue("extract"))
                .serviceTier(Property.ofValue(ChatCompletion.ServiceTier.AUTO))
                .tools(Property.ofValue(List.of(new ChatCompletion.Tool("extract", null, Map.of("type", "object")))))
                .build().run(runContextFactory.of());
            var request = requests.getLast();
            assertThat(request.path("tool_choice").path("type").asText(), is(choice.name().toLowerCase(java.util.Locale.ROOT)));
            if (choice == ChatCompletion.ToolChoice.TOOL) {
                assertThat(request.path("tool_choice").path("name").asText(), is("extract"));
            }
            assertThat(request.path("service_tier").asText(), is("auto"));
        }
    }

    @Test
    void shouldRejectInvalidOptionsBeforeCallingApi() {
        assertThrows(IllegalArgumentException.class, () -> task().thinking(Property.ofValue(true)).build().run(runContextFactory.of()));
        for (int budget : List.of(1, 4096)) {
            assertThrows(
                IllegalArgumentException.class, () -> task().thinking(Property.ofValue(true))
                    .budgetTokens(Property.ofValue(budget)).maxTokens(Property.ofValue(4096L)).build().run(runContextFactory.of())
            );
        }
        assertThrows(
            IllegalArgumentException.class, () -> task().toolChoice(Property.ofValue(ChatCompletion.ToolChoice.TOOL))
                .build().run(runContextFactory.of())
        );
        assertThrows(
            IllegalArgumentException.class, () -> task().thinking(Property.ofValue(true)).budgetTokens(Property.ofValue(1024))
                .maxTokens(Property.ofValue(4096L)).toolChoice(Property.ofValue(ChatCompletion.ToolChoice.ANY)).build().run(runContextFactory.of())
        );
        assertThrows(IllegalArgumentException.class, () -> task().maxRetries(Property.ofValue(-1)).build().run(runContextFactory.of()));
        assertThrows(IllegalArgumentException.class, () -> task().readTimeout(Property.ofValue(Duration.ofSeconds(-1))).build().run(runContextFactory.of()));
        assertThat(requests, empty());
    }

    @Test
    void shouldApplyRenderedClientConfigurationAndDisableRetries() {
        status = 500;
        response = "{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"Try later\"}}";
        assertThrows(
            Exception.class, () -> task()
                .baseUrl(Property.ofExpression("{{ endpoint }}"))
                .connectTimeout(Property.ofExpression("{{ connect }}"))
                .readTimeout(Property.ofExpression("{{ read }}"))
                .maxRetries(Property.ofExpression("{{ retries }}"))
                .build().run(runContextFactory.of(Map.of("endpoint", baseUrl, "connect", "PT2S", "read", "PT3S", "retries", 0)))
        );
        assertThat(requests, hasSize(1));
    }

    @Test
    void shouldApplyBothTimeoutsToSdkClient() throws Exception {
        var task = task().connectTimeout(Property.ofValue(Duration.ofSeconds(2)))
            .readTimeout(Property.ofValue(Duration.ofSeconds(3))).build();
        var client = task.buildClient(runContextFactory.of(), "test-key");
        try {
            client.withOptions(options ->
            {
                assertThat(options.timeout().connect(), is(Duration.ofSeconds(2)));
                assertThat(options.timeout().read(), is(Duration.ofSeconds(3)));
            });
        } finally {
            client.close();
        }
    }

    @Test
    void shouldRenderDocumentedYamlExamples() throws Exception {
        var examples = ChatCompletion.class.getAnnotation(Plugin.class).examples();
        var context = runContextFactory.of(
            Map.of(
                "inputs", Map.of(
                    "image_base64", "aW1hZ2U=", "pdf_base64", "cGRm", "image_file_id", "file_image", "pdf_file_id", "file_pdf"
                )
            )
        );
        for (int i = examples.length - 4; i < examples.length; i++) {
            var flow = JacksonMapper.ofYaml().readTree(String.join("\n", examples[i].code()));
            var task = JacksonMapper.ofJson().treeToValue(flow.path("tasks").path(0), ChatCompletion.class);
            var params = task.createParams(context);
            assertThat(params.messages(), not(empty()));
        }
    }

    @Test
    void shouldAccumulateStreamedTextThinkingToolsCitationsAndUsage() throws Exception {
        contentType = "text/event-stream";
        response = event("message_start", "{\"type\":\"message_start\",\"message\":" + message("[]") + "}")
            + event("content_block_start", "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"\"}}")
            + delta(0, "{\"type\":\"thinking_delta\",\"thinking\":\"Reason\"}")
            + delta(0, "{\"type\":\"signature_delta\",\"signature\":\"sig\"}")
            + event("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}")
            + event("content_block_start", "{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"text\",\"text\":\"\",\"citations\":[]}}")
            + delta(1, "{\"type\":\"text_delta\",\"text\":\"Hello\"}")
            + delta(1, "{\"type\":\"text_delta\",\"text\":\" world\"}")
            + delta(
                1,
                "{\"type\":\"citations_delta\",\"citation\":{\"type\":\"page_location\",\"cited_text\":\"Source\",\"document_index\":0,\"document_title\":\"Report\",\"start_page_number\":1,\"end_page_number\":2}}"
            )
            + event("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":1}")
            + event("content_block_start", "{\"type\":\"content_block_start\",\"index\":2,\"content_block\":{\"type\":\"tool_use\",\"id\":\"tool_1\",\"name\":\"extract\",\"input\":{}}}")
            + delta(2, "{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"name\\\":\"}")
            + delta(2, "{\"type\":\"input_json_delta\",\"partial_json\":\"\\\"Ada\\\"}\"}")
            + event("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":2}")
            + event("message_delta", "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":25}}")
            + event("message_stop", "{\"type\":\"message_stop\"}");
        var output = task().stream(Property.ofValue(true)).build().run(runContextFactory.of());
        assertThat(requests.getFirst().path("stream").asBoolean(), is(true));
        assertThat(output.getOutputText(), is("Hello world"));
        assertThat(output.getThinking(), is("Reason"));
        assertThat(output.getToolUses().getFirst().input(), is(Map.of("name", "Ada")));
        assertThat(output.getCitations().getFirst().documentTitle(), is("Report"));
        assertThat(output.getStopReason(), is("tool_use"));
        assertThat(output.getCacheCreationInputTokens(), is(3L));
        assertThat(output.getCacheReadInputTokens(), is(4L));
        assertThat(JacksonMapper.ofJson().readTree(output.getRawResponse()).path("usage").path("output_tokens").asInt(), is(25));
    }

    @Test
    void shouldFailOnIncompleteStream() {
        contentType = "text/event-stream";
        response = event("message_start", "{\"type\":\"message_start\",\"message\":" + message("[]") + "}");
        assertThrows(IllegalStateException.class, () -> task().stream(Property.ofValue(true)).build().run(runContextFactory.of()));
    }

    @Test
    void shouldWarnWhenToolInputIsNotAnObject() throws Exception {
        response = message("[{\"type\":\"tool_use\",\"id\":\"tool_1\",\"name\":\"extract\",\"input\":\"invalid-sensitive-input\"}]");
        var warnings = new CopyOnWriteArrayList<String>();
        var context = loggingContext(new CopyOnWriteArrayList<>(), warnings);
        var output = task().build().run(context);
        assertThat(output.getToolUses().getFirst().input(), nullValue());
        assertThat(warnings, hasItem(containsString("Unable to convert Anthropic tool input")));
        assertThat(warnings.toString(), not(containsString("invalid-sensitive-input")));
    }

    @Test
    void shouldWarnOnlyWhenThinkingOverridesTemperature() throws Exception {
        var warnings = new CopyOnWriteArrayList<String>();
        var context = loggingContext(new CopyOnWriteArrayList<>(), warnings);
        for (Double configured : new Double[] { null, 1.0, 0.5 }) {
            var builder = task().thinking(Property.ofValue(true)).budgetTokens(Property.ofValue(1024)).maxTokens(Property.ofValue(4096L));
            if (configured != null) {
                builder.temperature(Property.ofValue(configured));
            }
            builder.build().run(context);
            assertThat(requests.getLast().path("temperature").asDouble(), is(1.0));
        }
        task().temperature(Property.ofValue(0.5)).build().run(context);
        assertThat(requests.getLast().path("temperature").asDouble(), is(0.5));
        assertThat(warnings, contains("temperature is set to 1 because thinking is enabled; the configured temperature is ignored"));
    }

    @Test
    void shouldLogStreamedLinesAndFlushRemainingText() throws Exception {
        contentType = "text/event-stream";
        response = textStreamStart()
            + textDelta("first") + textDelta(" line\r") + textDelta("\nsecond\n\nfinal") + textDelta(" fragment")
            + textStreamEnd();
        var lines = new CopyOnWriteArrayList<String>();
        var output = task().stream(Property.ofValue(true)).build().run(loggingContext(lines, new CopyOnWriteArrayList<>()));
        assertThat(lines, contains("first line", "second", "", "final fragment"));
        assertThat(output.getOutputText(), is("first line\r\nsecond\n\nfinal fragment"));
    }

    @Test
    void shouldFlushPartialTextWhenStreamFails() throws Exception {
        contentType = "text/event-stream";
        response = textStreamStart() + textDelta("unfinished");
        var lines = new CopyOnWriteArrayList<String>();
        var context = loggingContext(lines, new CopyOnWriteArrayList<>());
        assertThrows(IllegalStateException.class, () -> task().stream(Property.ofValue(true)).build().run(context));
        assertThat(lines, contains("unfinished"));
    }

    @Test
    void shouldCancelAnOpenStreamWhenKilled() throws Exception {
        contentType = "text/event-stream";
        streamRelease = new CountDownLatch(1);
        response = textStreamStart() + textDelta("started\n");
        var started = new CountDownLatch(1);
        var context = spy(runContextFactory.of());
        var logger = mock(Logger.class);
        doReturn(logger).when(context).logger();
        doAnswer(invocation ->
        {
            started.countDown();
            return null;
        }).when(logger).info(eq("{}"), any(Object.class));
        var task = task().stream(Property.ofValue(true)).build();
        try (var executor = Executors.newCachedThreadPool()) {
            var execution = executor.submit(() -> task.run(context));
            try {
                assertThat(started.await(20, TimeUnit.SECONDS), is(true));
                var cancellation = executor.submit(task::kill);
                cancellation.get(5, TimeUnit.SECONDS);
                assertThrows(ExecutionException.class, () -> execution.get(5, TimeUnit.SECONDS));
                task.kill();
                assertThat(requests, hasSize(1));
            } finally {
                streamRelease.countDown();
                execution.cancel(true);
            }
        }
    }

    @Test
    void shouldRejectEnumTyposAndKeepLowercaseYamlValues() throws Exception {
        var mapper = JacksonMapper.ofYaml();
        for (String invalid : List.of(
            "type: typo\ntext: hello",
            "type: image\nsource: {type: typo}",
            "type: text\ntext: hello\ncacheControl: typo"
        )) {
            assertThrows(Exception.class, () -> mapper.readValue(invalid, ChatCompletion.ContentBlock.class));
        }
        assertThrows(Exception.class, () -> mapper.readValue("type: USER\ncontent: hello\ncacheControl: typo", ChatCompletion.ChatMessage.class));
        assertThrows(Exception.class, () -> mapper.readValue("text: hello\ncacheControl: typo", ChatCompletion.SystemPromptBlock.class));
        assertThrows(Exception.class, () -> mapper.readValue("name: extract\ncacheControl: typo", ChatCompletion.Tool.class));
        var block = mapper.readValue("type: image\nsource: {type: url, url: 'https://example.com/image.png'}\ncacheControl: ephemeral", ChatCompletion.ContentBlock.class);
        assertThat(block.type(), is(ChatCompletion.ContentBlockType.IMAGE));
        assertThat(block.source().type(), is(ChatCompletion.ContentSourceType.URL));
        assertThat(block.cacheControl(), is(ChatCompletion.CacheControl.EPHEMERAL));
        var serialized = JacksonMapper.ofJson().valueToTree(block);
        assertThat(serialized.path("type").asText(), is("image"));
        assertThat(serialized.path("source").path("type").asText(), is("url"));
        assertThat(serialized.path("cacheControl").asText(), is("ephemeral"));
    }

    private RunContext loggingContext(List<String> lines, List<String> warnings) {
        var context = spy(runContextFactory.of());
        var logger = mock(Logger.class);
        doReturn(logger).when(context).logger();
        doAnswer(invocation ->
        {
            lines.add(invocation.getArgument(1).toString());
            return null;
        }).when(logger).info(eq("{}"), any(Object.class));
        doAnswer(invocation ->
        {
            warnings.add(invocation.getArgument(0));
            return null;
        }).when(logger).warn(anyString());
        doAnswer(invocation ->
        {
            warnings.add(invocation.getArgument(0));
            return null;
        }).when(logger).warn(anyString(), any(Object.class));
        return context;
    }

    private static String textStreamStart() {
        return event("message_start", "{\"type\":\"message_start\",\"message\":" + message("[]") + "}")
            + event("content_block_start", "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}");
    }

    private static String textDelta(String text) throws Exception {
        return delta(0, JacksonMapper.ofJson().writeValueAsString(Map.of("type", "text_delta", "text", text)));
    }

    private static String textStreamEnd() {
        return event("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}")
            + event("message_delta", "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":25}}")
            + event("message_stop", "{\"type\":\"message_stop\"}");
    }

    private ChatCompletion.ChatCompletionBuilder<?, ?> task() {
        return ChatCompletion.builder().id("chat_" + UUID.randomUUID().toString().replace("-", ""))
            .apiKey(Property.ofValue("test-key")).baseUrl(Property.ofValue(baseUrl)).maxRetries(Property.ofValue(0))
            .model(Property.ofValue("claude-sonnet-4-6"))
            .messages(Property.ofValue(List.of(new ChatCompletion.ChatMessage(ChatCompletion.ChatMessageType.USER, "Hello"))));
    }

    private static String message(String content) {
        return "{\"id\":\"msg_test\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-sonnet-4-6\",\"content\":" + content
            + ",\"stop_reason\":\"end_turn\",\"stop_sequence\":null,\"usage\":{\"input_tokens\":10,\"output_tokens\":1,\"cache_creation_input_tokens\":3,\"cache_read_input_tokens\":4}}";
    }

    private static <T> Property<T> dynamic(Object value) throws Exception {
        return Property.ofExpression(JacksonMapper.ofJson().writeValueAsString(value));
    }

    private static String event(String type, String data) {
        return "event: " + type + "\ndata: " + data + "\n\n";
    }

    private static String delta(int index, String delta) {
        return event("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":" + index + ",\"delta\":" + delta + "}");
    }
}
