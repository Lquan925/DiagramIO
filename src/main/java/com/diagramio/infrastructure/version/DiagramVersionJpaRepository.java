package com.diagramio.infrastructure.version;

import com.diagramio.domain.version.DiagramVersionSummary;
import org.springframework.data.domain.Pageable;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DiagramVersionJpaRepository extends JpaRepository<DiagramVersionEntity, UUID> {

    @Query("""
            select new com.diagramio.domain.version.DiagramVersionSummary(
                v.id, v.versionNumber, v.note, v.createdBy, v.createdAt)
            from DiagramVersionEntity v
            where v.diagramId = :diagramId
            order by v.versionNumber desc
            """)
    List<DiagramVersionSummary> findByDiagramIdOrderByVersionNumberDesc(@Param("diagramId") UUID diagramId, Pageable pageable);

    long countByDiagramId(UUID diagramId);

    Optional<DiagramVersionEntity> findByIdAndDiagramId(UUID id, UUID diagramId);

    @Query("select coalesce(max(v.versionNumber), 0) from DiagramVersionEntity v where v.diagramId = :diagramId")
    int maxVersionNumber(@Param("diagramId") UUID diagramId);

    /** SELECT ... FOR UPDATE: chờ đến khi giao dịch đang giữ dòng sơ đồ commit hoặc rollback. */
    @Query(value = "select id from diagrams where id = :diagramId for update", nativeQuery = true)
    List<UUID> lockDiagramRow(@Param("diagramId") UUID diagramId);
}
