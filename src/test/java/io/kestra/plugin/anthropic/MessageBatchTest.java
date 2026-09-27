package io.kestra.plugin.anthropic;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;

import jakarta.inject.Inject;
import jakarta.validation.ConstraintViolationException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class MessageBatchTest {
    private static final String API_KEY = System.getenv("ANTHROPIC_API_KEY");

    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void shouldRejectCreateWithoutRequests() {
        var task = MessageBatch.builder()
            .apiKey(Property.ofValue("test-key"))
            .mode(Property.ofValue(MessageBatch.Mode.CREATE))
            .build();

        var exception = assertThrows(IllegalArgumentException.class, () -> task.run(context()));

        assertThat(exception.getMessage(), containsString("requests"));
    }

    @Test
    void shouldRejectRetrieveWithoutBatchId() {
        var task = MessageBatch.builder()
            .apiKey(Property.ofValue("test-key"))
            .mode(Property.ofExpression("{{ mode }}"))
            .build();

        var exception = assertThrows(
            IllegalArgumentException.class,
            () -> task.run(runContextFactory.of(Map.of("mode", "RETRIEVE")))
        );

        assertThat(exception.getMessage(), containsString("batchId"));
    }

    @Test
    void shouldRejectBlankCustomId() {
        var task = createTask(List.of(request("  ", "Hello")));

        assertThrows(ConstraintViolationException.class, () -> task.run(context()));
    }

    @Test
    void shouldRejectDuplicateCustomIds() {
        var task = createTask(List.of(request("same", "Hello"), request("same", "Again")));

        var exception = assertThrows(IllegalArgumentException.class, () -> task.run(context()));

        assertThat(exception.getMessage(), containsString("same"));
    }

    @Test
    void shouldRejectEmptyMessages() {
        var task = createTask(
            List.of(
                MessageBatch.BatchRequest.builder()
                    .customId("empty")
                    .model("claude-sonnet-4-6")
                    .messages(List.of())
                    .build()
            )
        );

        assertThrows(ConstraintViolationException.class, () -> task.run(context()));
    }

    @EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".*")
    @Test
    void shouldCreateAndCancelBatch() throws Exception {
        var runContext = apiContext();
        var created = liveCreateTask(
            List.of(
                request("cancel-me", "Reply with the single word pending.")
            )
        ).run(runContext);

        assertThat(created.getBatchId(), startsWith("msgbatch_"));
        assertThat(created.getStatus(), anyOf(is("in_progress"), is("ended"), is("canceling")));
        assertThat(created.getRequestCounts(), notNullValue());
        assertThat(created.getResults(), nullValue());

        var canceled = MessageBatch.builder()
            .apiKey(Property.ofExpression("{{ apiKey }}"))
            .mode(Property.ofValue(MessageBatch.Mode.CANCEL))
            .batchId(Property.ofValue(created.getBatchId()))
            .build()
            .run(runContext);

        assertThat(canceled.getBatchId(), is(created.getBatchId()));
        assertThat(canceled.getStatus(), anyOf(is("canceling"), is("ended")));
    }

    @EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".*")
    @Test
    void shouldRetrieveUntilEnded() throws Exception {
        var runContext = apiContext();
        var created = liveCreateTask(
            List.of(
                request("capital-japan", "What is the capital of Japan? Answer with one word and no punctuation."),
                request("capital-france", "What is the capital of France? Answer with one word and no punctuation.")
            )
        ).run(runContext);

        var deadline = System.nanoTime() + Duration.ofMinutes(3).toNanos();
        MessageBatch.Output retrieved = null;
        while (System.nanoTime() < deadline) {
            retrieved = MessageBatch.builder()
                .apiKey(Property.ofExpression("{{ apiKey }}"))
                .mode(Property.ofValue(MessageBatch.Mode.RETRIEVE))
                .batchId(Property.ofValue(created.getBatchId()))
                .build()
                .run(runContext);
            if ("ended".equals(retrieved.getStatus())) {
                break;
            }
            assertThat(retrieved.getResults(), nullValue());
            Thread.sleep(Duration.ofSeconds(5));
        }

        assertThat(retrieved, notNullValue());
        assertThat(retrieved.getStatus(), is("ended"));
        assertThat(retrieved.getResultsUrl(), notNullValue());
        assertThat(
            retrieved.getResults().stream().map(MessageBatch.RequestResult::getCustomId).toList(),
            containsInAnyOrder("capital-japan", "capital-france")
        );
        assertThat(retrieved.getResults(), everyItem(hasProperty("type", is("succeeded"))));
        assertThat(retrieved.getResults(), everyItem(hasProperty("outputText", not(blankOrNullString()))));
    }

    private MessageBatch createTask(List<MessageBatch.BatchRequest> requests) {
        return batch(Property.ofValue("test-key"), requests);
    }

    private MessageBatch liveCreateTask(List<MessageBatch.BatchRequest> requests) {
        return batch(Property.ofExpression("{{ apiKey }}"), requests);
    }

    private MessageBatch batch(Property<String> apiKey, List<MessageBatch.BatchRequest> requests) {
        return MessageBatch.builder()
            .apiKey(apiKey)
            .mode(Property.ofValue(MessageBatch.Mode.CREATE))
            .requests(Property.ofValue(requests))
            .build();
    }

    private MessageBatch.BatchRequest request(String customId, String content) {
        return MessageBatch.BatchRequest.builder()
            .customId(customId)
            .model("claude-sonnet-4-6")
            .maxTokens(32L)
            .messages(
                List.of(
                    ChatCompletion.ChatMessage.builder()
                        .type(ChatCompletion.ChatMessageType.USER)
                        .content(content)
                        .build()
                )
            )
            .build();
    }

    private RunContext context() {
        return runContextFactory.of(Map.of());
    }

    private RunContext apiContext() {
        return runContextFactory.of(Map.of("apiKey", API_KEY, "model", "claude-sonnet-4-6"));
    }
}
