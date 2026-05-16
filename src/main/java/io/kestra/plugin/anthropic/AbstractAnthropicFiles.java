package io.kestra.plugin.anthropic;

import java.net.URI;
import java.nio.file.Paths;
import java.util.Optional;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.beta.AnthropicBeta;
import com.anthropic.models.beta.files.BetaFileScope;
import com.anthropic.models.beta.files.FileMetadata;

import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;

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
public abstract class AbstractAnthropicFiles extends Task {
    protected static final AnthropicBeta FILES_BETA = AnthropicBeta.FILES_API_2025_04_14;

    @Schema(title = "Anthropic API Key")
    @NotNull
    @PluginProperty(secret = true, group = "main")
    protected Property<String> apiKey;

    protected AnthropicClient buildClient(String rApiKey) {
        return AnthropicOkHttpClient.builder()
            .apiKey(rApiKey)
            .build();
    }

    protected String resolveFileName(RunContext runContext, URI fileUri, Optional<String> override) {
        if (override.isPresent() && !override.get().isBlank()) {
            return override.get();
        }

        try {
            var attributes = runContext.storage().getAttributes(fileUri);
            if (attributes != null && attributes.getFileName() != null && !attributes.getFileName().isBlank()) {
                return attributes.getFileName();
            }
        } catch (Exception ignored) {
            // Ignore and fall back to parsing the URI path.
        }

        if (fileUri.getPath() != null) {
            var fileName = Paths.get(fileUri.getPath()).getFileName();
            if (fileName != null && !fileName.toString().isBlank()) {
                return fileName.toString();
            }
        }

        return "upload.bin";
    }

    protected FileMetadataInfo toMetadataInfo(FileMetadata metadata) {
        String scopeId = metadata.scope().map(BetaFileScope::id).orElse(null);

        return FileMetadataInfo.builder()
            .fileId(metadata.id())
            .filename(metadata.filename())
            .mimeType(metadata.mimeType())
            .size(metadata.sizeBytes())
            .createdAt(metadata.createdAt().toString())
            .downloadable(metadata.downloadable().orElse(null))
            .scopeId(scopeId)
            .build();
    }
}
