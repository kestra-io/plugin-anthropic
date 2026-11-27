package io.kestra.plugin.anthropic;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Builder
@Getter
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ModelInfo {
    @Schema(title = "Model ID", description = "Unique identifier for the model.")
    private String id;

    @Schema(title = "Display name", description = "Human-friendly name for the model.")
    private String displayName;

    @Schema(title = "Created at", description = "RFC 3339 timestamp when the model was created.")
    private String createdAt;
}
