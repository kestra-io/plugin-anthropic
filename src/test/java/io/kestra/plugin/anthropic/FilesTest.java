package io.kestra.plugin.anthropic;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;

import jakarta.inject.Inject;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@KestraTest
public class FilesTest {

    private final String ANTHROPIC_API_KEY = System.getenv("ANTHROPIC_API_KEY");

    @Inject
    private RunContextFactory runContextFactory;

    @EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".*")
    @Test
    void shouldUploadListGetAndDeleteFile() throws Exception {
        var runContext = runContextFactory.of(Map.of("apiKey", ANTHROPIC_API_KEY));

        URI storedFile = runContext.storage().putFile(
            new ByteArrayInputStream("Hello from Kestra".getBytes(StandardCharsets.UTF_8)),
            "sample.txt"
        );

        var uploadTask = UploadFile.builder()
            .apiKey(Property.ofExpression("{{ apiKey }}"))
            .filePath(Property.ofValue(storedFile.toString()))
            .mimeType(Property.ofValue("text/plain"))
            .build();

        var uploadOutput = uploadTask.run(runContext);

        assertThat(uploadOutput, notNullValue());
        assertThat(uploadOutput.getFileId(), notNullValue());

        var listTask = ListFiles.builder()
            .apiKey(Property.ofExpression("{{ apiKey }}"))
            .limit(Property.ofValue(50))
            .build();

        var listOutput = listTask.run(runContext);

        assertThat(listOutput, notNullValue());
        assertThat(listOutput.getFiles(), notNullValue());

        var getTask = GetFile.builder()
            .apiKey(Property.ofExpression("{{ apiKey }}"))
            .fileId(Property.ofValue(uploadOutput.getFileId()))
            .build();

        var getOutput = getTask.run(runContext);

        assertThat(getOutput, notNullValue());
        assertThat(getOutput.getMetadata(), notNullValue());
        assertThat(getOutput.getMetadata().getFileId(), is(uploadOutput.getFileId()));

        var deleteTask = DeleteFile.builder()
            .apiKey(Property.ofExpression("{{ apiKey }}"))
            .fileId(Property.ofValue(uploadOutput.getFileId()))
            .build();

        var deleteOutput = deleteTask.run(runContext);

        assertThat(deleteOutput, notNullValue());
        assertThat(deleteOutput.getDeleted(), is(true));
    }
}
