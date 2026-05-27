package io.kestra.plugin.anthropic;

import java.net.URI;
import java.nio.file.Paths;
import java.util.Optional;

import com.anthropic.models.beta.AnthropicBeta;
import com.anthropic.models.beta.files.BetaFileScope;
import com.anthropic.models.beta.files.FileMetadata;

import io.kestra.core.runners.RunContext;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractAnthropicFiles extends AbstractAnthropic {
    protected static final AnthropicBeta FILES_BETA = AnthropicBeta.FILES_API_2025_04_14;
    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractAnthropicFiles.class);

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
            LOGGER.debug("Failed to read storage attributes for {}: {}", fileUri, ignored.getMessage());
            if (LOGGER.isTraceEnabled()) {
                LOGGER.trace("Exception while resolving filename for {}", fileUri, ignored);
            }
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
        if (LOGGER.isDebugEnabled()) {
            String scopeIdLog = metadata.scope().map(BetaFileScope::id).orElse(null);
            LOGGER.debug("Converting FileMetadata to FileMetadataInfo: id={}, filename={}, mimeType={}, size={}, scopeId={}",
                metadata.id(), metadata.filename(), metadata.mimeType(), metadata.sizeBytes(), scopeIdLog);
        }
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
