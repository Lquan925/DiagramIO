package com.diagramio.domain.diagram;

import java.util.List;

/** Page content and the total number of diagrams matching the same filters. */
public record DiagramPage(List<Diagram> content, long totalElements) {
    public DiagramPage {
        content = List.copyOf(content);
    }
}
