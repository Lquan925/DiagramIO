package com.diagramio.domain.version;

import java.time.Instant;
import java.util.UUID;

/** Metadata for a version history entry, without the snapshot payload. */
public record DiagramVersionSummary(UUID id, int versionNumber, String note,
                                    UUID createdBy, Instant createdAt) {
    public static DiagramVersionSummary from(DiagramVersion version) {
        return new DiagramVersionSummary(version.id(), version.versionNumber(),
                version.note(), version.createdBy(), version.createdAt());
    }
}
