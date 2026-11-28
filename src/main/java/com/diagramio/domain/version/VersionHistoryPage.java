package com.diagramio.domain.version;

import java.util.List;

/** Metadata page; contains no persistence or web types. */
public record VersionHistoryPage(List<DiagramVersionSummary> content, int page, int size, long totalElements) {
    public VersionHistoryPage { content = List.copyOf(content); }
}
