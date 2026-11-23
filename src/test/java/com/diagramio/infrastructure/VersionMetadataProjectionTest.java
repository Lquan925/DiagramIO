package com.diagramio.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.diagramio.domain.access.ShareRole;
import com.diagramio.domain.diagram.Diagram;
import com.diagramio.domain.diagram.DiagramData;
import com.diagramio.domain.diagram.DiagramType;
import com.diagramio.domain.exception.ConflictException;
import com.diagramio.domain.exception.OptimisticLockConflictException;
import com.diagramio.domain.share.DiagramShare;
import com.diagramio.domain.version.DiagramVersion;
import com.diagramio.infrastructure.diagram.DiagramMapper;
import com.diagramio.infrastructure.diagram.DiagramRepositoryAdapter;
import com.diagramio.infrastructure.share.DiagramShareMapper;

import com.diagramio.infrastructure.version.DiagramVersionRepositoryAdapter;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** SQL contract for version history metadata queries. */
@DataJpaTest(properties = "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.diagramio.infrastructure.VersionMetadataProjectionTest$SqlCapture")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(DiagramVersionRepositoryAdapter.class)
@Testcontainers(disabledWithoutDocker = true)
class VersionMetadataProjectionTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired
    private DiagramVersionRepositoryAdapter versionRepository;

    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    private static final UUID SEED_USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final DiagramData DATA = DiagramData.fromStorage("{\"nodes\": [], \"edges\": []}");

    private UUID newDiagramId() {
        UUID id = UUID.randomUUID();
        entityManager.createNativeQuery("insert into diagrams (id, title, diagram_type, data, version, is_public, owner_id, updated_by, created_at, updated_at) values (:id, 'Projection', 'UML', cast(:data as jsonb), 1, false, :owner, :owner, now(), now())")
                .setParameter("id", id).setParameter("data", DATA.json()).setParameter("owner", SEED_USER)
                .executeUpdate();
        return id;
    }

    public static class SqlCapture implements org.hibernate.resource.jdbc.spi.StatementInspector {
        static final ThreadLocal<java.util.List<String>> statements =
                ThreadLocal.withInitial(java.util.ArrayList::new);
        public String inspect(String sql) {
            statements.get().add(sql);
            return sql;
        }
    }

    @Test
    void historySelectsOnlyMetadataIncludingNulls() {
        UUID diagramId = newDiagramId();
        DiagramVersion version = new DiagramVersion(UUID.randomUUID(), diagramId, 1,
                DATA, null, null, Instant.now().truncatedTo(ChronoUnit.MICROS));
        versionRepository.save(version);
        SqlCapture.statements.get().clear();
        assertThat(versionRepository.findByDiagramId(diagramId))
                .containsExactly(com.diagramio.domain.version.DiagramVersionSummary.from(version));
        assertThat(SqlCapture.statements.get()).hasSize(1);
        String sql = SqlCapture.statements.get().getFirst().toLowerCase(java.util.Locale.ROOT);
        assertThat(sql).contains("from diagram_versions");
        assertThat(sql.substring(0, sql.indexOf(" from ")))
                .doesNotContain(".data", "*", "diagram_id")
                .contains(".id", ".version_number", ".note", ".created_by", ".created_at");
        assertThat(versionRepository.findByDiagramId(UUID.randomUUID())).isEmpty();
        SqlCapture.statements.remove();
    }

}
