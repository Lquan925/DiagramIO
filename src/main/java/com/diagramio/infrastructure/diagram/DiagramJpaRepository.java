package com.diagramio.infrastructure.diagram;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DiagramJpaRepository extends JpaRepository<DiagramEntity, UUID> {
}
