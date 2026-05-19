package io.kestra.plugin.anthropic;

import java.io.InputStream;
import java.net.URI;

import com.anthropic.core.MultipartField;
import com.anthropic.models.beta.files.FileUploadParams;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode(callSuper = true)
@Getter
@NoArgsConstructor
@Schema(
    title = "Upload a file to Anthropic",
    description = "Uploads a file from Kestra internal storage to the Anthropic Files API using the beta Files endpoint."
)
@Plugin(
    examples = {
        @Example(
            title = "Upload a text file",
            full = true,
            code = """
                id: anthropic_upload_file
                namespace: company.team

                tasks:
                  - id: upload
                    type: io.kestra.plugin.anthropic.UploadFile
                    apiKey: "{{ secret('ANTHROPIC_API_KEY') }}"
                    filePath: "{{ outputs.create_file.uri }}"
                    mimeType: "text/plain"
            """
        )
    }
)
public class UploadFile extends AbstractAnthropicFiles implements RunnableTask<UploadFile.Output> {

    @Schema(
        title = "File path",
        description = "Kestra internal storage URI (for example, from a previous task output)."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> filePath;

    @Schema(
        title = "MIME type",
        description = "Content type to send with the file (e.g., text/plain, image/png, application/pdf)."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> mimeType;

    @Schema(
        title = "Filename override",
        description = "Optional filename to send to Anthropic. If unset, the Kestra storage filename is used."
    )
    @PluginProperty(group = "advanced")
    private Property<String> filename;

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rApiKey = runContext.render(apiKey).as(String.class).orElseThrow();
        var rFilePath = runContext.render(filePath).as(String.class).orElseThrow();
        var rMimeType = runContext.render(mimeType).as(String.class).orElseThrow();
        var rFilename = runContext.render(filename).as(String.class);

        if (rMimeType.isBlank()) {
            throw new IllegalArgumentException("mimeType must not be blank");
        }

        URI fileUri = URI.create(rFilePath);
        String resolvedFilename = resolveFileName(runContext, fileUri, rFilename);

        var client = buildClient(rApiKey);

        try (InputStream inputStream = runContext.storage().getFile(fileUri)) {
            var multipart = MultipartField.<InputStream>builder()
                .value(inputStream)
                .filename(resolvedFilename)
                .contentType(rMimeType)
                .build();

            var params = FileUploadParams.builder()
                .addBeta(FILES_BETA)
                .file(multipart)
                .build();

            var response = client.beta().files().upload(params);

            return Output.builder()
                .fileId(response.id())
                .filename(response.filename())
                .size(response.sizeBytes())
                .build();
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "File ID", description = "Identifier of the uploaded file.")
        private String fileId;

        @Schema(title = "Filename", description = "Filename assigned by Anthropic.")
        private String filename;

        @Schema(title = "Size", description = "File size in bytes.")
        private Long size;
    }
}
