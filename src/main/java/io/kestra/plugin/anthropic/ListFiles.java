package io.kestra.plugin.anthropic;

import java.util.List;

import com.anthropic.models.beta.files.FileListParams;
import io.swagger.v3.oas.annotations.media.Schema;
import io.kestra.core.runners.RunContext;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;

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
    title = "List Anthropic files",
    description = "Lists files stored in the Anthropic Files API using the beta Files endpoint."
)
@Plugin(
    examples = {
        @Example(
            title = "List files",
            full = true,
            code = """
                id: anthropic_list_files
                namespace: company.team

                tasks:
                  - id: list_files
                    type: io.kestra.plugin.anthropic.ListFiles
                    apiKey: "{{ secret('ANTHROPIC_API_KEY') }}"
                    limit: 25
            """
        )
    }
)
public class ListFiles extends AbstractAnthropicFiles implements RunnableTask<ListFiles.Output> {

    @Schema(
        title = "After ID",
        description = "Cursor for pagination; returns results after this file ID."
    )
    @PluginProperty(group = "advanced")
    private Property<String> afterId;

    @Schema(
        title = "Before ID",
        description = "Cursor for pagination; returns results before this file ID."
    )
    @PluginProperty(group = "advanced")
    private Property<String> beforeId;

    @Schema(
        title = "Limit",
        description = "Maximum number of files to return (1-1000)."
    )
    @PluginProperty(group = "advanced")
    private Property<Integer> limit;

    @Schema(
        title = "Scope ID",
        description = "Filter by scope ID, such as a session ID."
    )
    @PluginProperty(group = "advanced")
    private Property<String> scopeId;

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rApiKey = runContext.render(apiKey).as(String.class).orElseThrow();
        var rAfterId = runContext.render(afterId).as(String.class);
        var rBeforeId = runContext.render(beforeId).as(String.class);
        var rLimit = runContext.render(limit).as(Integer.class);
        var rScopeId = runContext.render(scopeId).as(String.class);

        var client = buildClient(rApiKey);

        var paramsBuilder = FileListParams.builder().addBeta(FILES_BETA);
        rAfterId.ifPresent(paramsBuilder::afterId);
        rBeforeId.ifPresent(paramsBuilder::beforeId);
        rLimit.ifPresent(value -> {
            if (value < 1 || value > 1000) {
                throw new IllegalArgumentException("limit must be between 1 and 1000");
            }
            paramsBuilder.limit(value.longValue());
        });
        rScopeId.ifPresent(paramsBuilder::scopeId);

        var page = client.beta().files().list(paramsBuilder.build());

        List<FileMetadataInfo> files = page.items().stream()
            .map(this::toMetadataInfo)
            .toList();

        return Output.builder()
            .files(files)
            .firstId(page.firstId().orElse(null))
            .lastId(page.lastId().orElse(null))
            .hasMore(page.hasMore().orElse(null))
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Files", description = "List of files returned by Anthropic.")
        private List<FileMetadataInfo> files;

        @Schema(title = "First ID", description = "First file ID in the result page.")
        private String firstId;

        @Schema(title = "Last ID", description = "Last file ID in the result page.")
        private String lastId;

        @Schema(title = "Has more", description = "Whether more results are available.")
        private Boolean hasMore;
    }
}
