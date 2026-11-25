package com.diagramio.domain.diagram;

import java.time.Instant;
import java.util.UUID;

/** Metadata for a diagram list, without loading its JSON content. */
public record DiagramSummary(
        UUID id,
        String title,
        DiagramType diagramType,
        Integer version,
        boolean isPublic,
        UUID ownerId,
        UUID updatedBy,
        Instant createdAt,
        Instant updatedAt) {
}
