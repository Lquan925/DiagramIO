package com.diagramio.infrastructure.diagram;

import com.diagramio.domain.diagram.Diagram;
import com.diagramio.domain.diagram.DiagramData;
import com.diagramio.domain.diagram.DiagramPage;
import com.diagramio.domain.diagram.DiagramRepository;
import com.diagramio.domain.diagram.DiagramType;
import com.diagramio.domain.exception.OptimisticLockConflictException;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Repository;

@Repository
public class DiagramRepositoryAdapter implements DiagramRepository {

    private final DiagramJpaRepository repository;
    private final DiagramMapper mapper;
    private final EntityManager entityManager;

    public DiagramRepositoryAdapter(DiagramJpaRepository repository, DiagramMapper mapper, EntityManager entityManager) {
        this.repository = repository;
        this.mapper = mapper;
        this.entityManager = entityManager;
    }

    /**
     * Ghi ngay (saveAndFlush) để @Version kiểm tra và tăng version trong cùng lời gọi:
     * nếu người khác vừa ghi trước thì trả về 409 thay vì lỗi lúc commit.
     */
    @Override
    public Diagram save(Diagram diagram) {
        try {
            return mapper.toDomain(repository.saveAndFlush(mapper.toEntity(diagram)));
        } catch (OptimisticLockingFailureException ex) {
            Integer currentVersion = repository.findById(diagram.getId()).map(DiagramEntity::getVersion).orElse(null);
            throw new OptimisticLockConflictException(diagram.getId(), diagram.getVersion(), currentVersion);
        }
    }

    @Override
    public Optional<Diagram> findById(UUID id) {
        return repository.findById(id).map(mapper::toDomain);
    }

    @Override
    public DiagramPage findByOwnerId(UUID ownerId, DiagramType diagramType, String title, int page, int size) {
        var parameters = new LinkedHashMap<String, Object>();
        parameters.put("ownerId", ownerId);
        String filter = "owner_id = :ownerId";
        if (diagramType != null) {
            filter += " AND diagram_type = :diagramType";
            parameters.put("diagramType", diagramType.name());
        }
        if (title != null && !title.isBlank()) {
            filter += " AND upper(title) LIKE upper(:title) ESCAPE '!'";
            parameters.put("title", "%" + title.replace("!", "!!").replace("%", "!%")
                    .replace("_", "!_") + "%");
        }
        return findPage(filter, parameters, page, size);
    }

    @Override
    public DiagramPage findPublicDiagrams(int page, int size) {
        return findPage("is_public = true", Map.of(), page, size);
    }

    @Override
    public void deleteById(UUID id) {
        repository.deleteById(id);
    }

    /** A single SQL returns page content and a total, including for an empty page. */
    private DiagramPage findPage(String filter, Map<String, Object> parameters, int page, int size) {
        // Only fixed SQL fragments are interpolated; user input is bound as parameters.
        // NOT MATERIALIZED lets PostgreSQL plan the count and limited page independently.
        String sql = """
                WITH filtered AS NOT MATERIALIZED (
                    SELECT id, title, diagram_type, version, is_public, owner_id,
                           updated_by, created_at, updated_at, data
                    FROM {h-schema}diagrams WHERE %s
                )
                SELECT p.id, p.title, p.diagram_type, p.version, p.is_public, p.owner_id,
                       p.updated_by, p.created_at, p.updated_at, CAST(p.data AS text), totals.total
                FROM (SELECT count(*) AS total FROM filtered) totals
                LEFT JOIN (
                    SELECT * FROM filtered
                    ORDER BY updated_at DESC, id DESC
                    LIMIT :size OFFSET :offset
                ) p ON true
                ORDER BY p.updated_at DESC, p.id DESC
                """.formatted(filter);
        var query = entityManager.createNativeQuery(sql);
        parameters.forEach(query::setParameter);
        query.setParameter("size", size);
        query.setParameter("offset", (long) page * size);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        var content = new ArrayList<Diagram>();
        for (Object[] row : rows) {
            // LEFT JOIN preserves the total-only row when the requested page is empty.
            if (row[0] != null) {
                content.add(new Diagram((UUID) row[0], (String) row[1],
                        DiagramType.valueOf((String) row[2]), DiagramData.fromStorage((String) row[9]),
                        ((Number) row[3]).intValue(), (Boolean) row[4], (UUID) row[5], (UUID) row[6],
                        toInstant(row[7]), toInstant(row[8])));
            }
        }
        return new DiagramPage(content, ((Number) rows.getFirst()[10]).longValue());
    }

    private Instant toInstant(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof Timestamp timestamp ? timestamp.toInstant() : Instant.from((TemporalAccessor) value);
    }
}
