package com.diagramio.domain.diagram;

import java.util.Optional;
import java.util.UUID;

public interface DiagramRepository {
    Diagram save(Diagram diagram);

    Optional<Diagram> findById(UUID id);

    /** diagramType null = không lọc theo loại; title rỗng = không tìm theo tên. */
    DiagramPage findByOwnerId(UUID ownerId, DiagramType diagramType, String title, int page, int size);

    DiagramPage findPublicDiagrams(int page, int size);

    void deleteById(UUID id);
}
