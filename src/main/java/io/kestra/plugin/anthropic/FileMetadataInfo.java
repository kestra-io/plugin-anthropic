package io.kestra.plugin.anthropic;

import io.swagger.v3.oas.annotations.media.Schema;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Builder
@Getter
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class FileMetadataInfo {
    @Schema(title = "File ID", description = "Unique identifier for the file.")
    private String fileId;

    @Schema(title = "Filename", description = "Original filename of the uploaded file.")
    private String filename;

    @Schema(title = "MIME type", description = "MIME type of the file.")
    private String mimeType;

    @Schema(title = "Size", description = "File size in bytes.")
    private Long size;

    @Schema(title = "Created at", description = "RFC 3339 timestamp of file creation.")
    private String createdAt;

    @Schema(title = "Downloadable", description = "Whether the file can be downloaded.")
    private Boolean downloadable;

    @Schema(title = "Scope ID", description = "Scope identifier for the file (e.g., session ID).")
    private String scopeId;
}
