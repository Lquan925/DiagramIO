package com.diagramio.domain.diagram;

import java.util.List;

/** Summary page content and the total number of diagrams matching the same filters. */
public record DiagramPage(List<DiagramSummary> content, long totalElements) {
    public DiagramPage {
        content = List.copyOf(content);
    }
}
