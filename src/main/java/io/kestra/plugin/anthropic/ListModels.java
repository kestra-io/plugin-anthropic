package io.kestra.plugin.anthropic;

import java.util.List;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
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
    title = "List Anthropic models",
    description = "Lists models available to the configured Anthropic API key."
)
@Plugin(
    examples = {
        @Example(
            title = "List available models",
            full = true,
            code = """
                id: anthropic_list_models
                namespace: company.team

                tasks:
                  - id: list_models
                    type: io.kestra.plugin.anthropic.ListModels
                    apiKey: "{{ secret('ANTHROPIC_API_KEY') }}"
            """
        )
    }
)
public class ListModels extends AbstractAnthropicBase implements RunnableTask<ListModels.Output> {

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rApiKey = runContext.render(apiKey).as(String.class).orElseThrow();
        var client = buildClient(rApiKey);

        var page = client.models().list();

        List<ModelInfo> models = page.items().stream()
            .map(this::toModelInfo)
            .toList();

        return Output.builder()
            .models(models)
            .build();
    }

    private ModelInfo toModelInfo(com.anthropic.models.models.ModelInfo model) {
        return ModelInfo.builder()
            .id(model.id())
            .displayName(model.displayName())
            .createdAt(model.createdAt().toString())
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Models", description = "List of models returned by Anthropic.")
        private List<ModelInfo> models;
    }
}
