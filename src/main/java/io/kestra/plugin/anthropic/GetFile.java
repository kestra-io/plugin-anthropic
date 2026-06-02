package io.kestra.plugin.anthropic;

import com.anthropic.models.beta.files.FileRetrieveMetadataParams;

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
    title = "Get Anthropic file metadata",
    description = "Retrieves metadata for a file stored in the Anthropic Files API using the beta Files endpoint."
)
@Plugin(
    examples = {
        @Example(
            title = "Get file metadata",
            full = true,
            code = """
                id: anthropic_get_file
                namespace: company.team

                tasks:
                  - id: get_file
                    type: io.kestra.plugin.anthropic.GetFile
                    apiKey: "{{ secret('ANTHROPIC_API_KEY') }}"
                    fileId: "{{ outputs.upload.fileId }}"
            """
        )
    }
)
public class GetFile extends AbstractAnthropicFiles implements RunnableTask<GetFile.Output> {

    @Schema(title = "File ID", description = "Identifier of the file to retrieve.")
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> fileId;

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rApiKey = runContext.render(apiKey).as(String.class).orElseThrow();
        var rFileId = runContext.render(fileId).as(String.class).orElseThrow();

        var client = buildClient(rApiKey);

        var params = FileRetrieveMetadataParams.builder()
            .addBeta(FILES_BETA)
            .fileId(rFileId)
            .build();

        var response = client.beta().files().retrieveMetadata(params);

        return Output.builder()
            .metadata(toMetadataInfo(response))
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Metadata", description = "Metadata for the requested file.")
        private FileMetadataInfo metadata;
    }
}
