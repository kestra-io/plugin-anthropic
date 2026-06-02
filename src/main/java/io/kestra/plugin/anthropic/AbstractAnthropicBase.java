package io.kestra.plugin.anthropic;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractAnthropicBase extends Task {

    @Schema(title = "Anthropic API Key")
    @NotNull
    @PluginProperty(secret = true, group = "main")
    protected Property<String> apiKey;

    protected AnthropicClient buildClient(String rApiKey) {
        return AnthropicOkHttpClient.builder()
            .apiKey(rApiKey)
            .build();
    }
}
