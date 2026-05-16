package io.kestra.plugin.anthropic;

import com.anthropic.models.beta.files.DeletedFile;
import com.anthropic.models.beta.files.FileDeleteParams;

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
    title = "Delete an Anthropic file",
    description = "Deletes a file in the Anthropic Files API using the beta Files endpoint."
)
@Plugin(
    examples = {
        @Example(
            title = "Delete a file",
            full = true,
            code = """
                id: anthropic_delete_file
                namespace: company.team

                tasks:
                  - id: delete_file
                    type: io.kestra.plugin.anthropic.DeleteFile
                    apiKey: "{{ secret('ANTHROPIC_API_KEY') }}"
                    fileId: "{{ outputs.upload.fileId }}"
            """
        )
    }
)
public class DeleteFile extends AbstractAnthropicFiles implements RunnableTask<DeleteFile.Output> {

    @Schema(title = "File ID", description = "Identifier of the file to delete.")
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> fileId;

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rApiKey = runContext.render(apiKey).as(String.class).orElseThrow();
        var rFileId = runContext.render(fileId).as(String.class).orElseThrow();

        var client = buildClient(rApiKey);

        var params = FileDeleteParams.builder()
            .addBeta(FILES_BETA)
            .fileId(rFileId)
            .build();

        DeletedFile response = client.beta().files().delete(params);
        boolean deleted = response.type()
            .map(type -> type.value() == DeletedFile.Type.Value.FILE_DELETED)
            .orElse(true);

        return Output.builder()
            .fileId(response.id())
            .deleted(deleted)
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "File ID", description = "Identifier of the deleted file.")
        private String fileId;

        @Schema(title = "Deleted", description = "Whether the delete was successful.")
        private Boolean deleted;
    }
}
