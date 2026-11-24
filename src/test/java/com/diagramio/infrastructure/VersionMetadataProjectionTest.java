package com.diagramio.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.diagramio.domain.diagram.DiagramData;
import com.diagramio.domain.version.DiagramVersion;

import com.diagramio.infrastructure.version.DiagramVersionRepositoryAdapter;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
        assertThat(versionRepository.findByDiagramId(diagramId, 0, 20))
                .containsExactly(com.diagramio.domain.version.DiagramVersionSummary.from(version));
        assertThat(SqlCapture.statements.get()).hasSize(1);
        String sql = SqlCapture.statements.get().getFirst().toLowerCase(java.util.Locale.ROOT);
        assertThat(sql).contains("from diagram_versions");
        assertThat(sql.substring(0, sql.indexOf(" from ")))
                .doesNotContain(".data", "*", "diagram_id")
                .contains(".id", ".version_number", ".note", ".created_by", ".created_at");
        assertThat(versionRepository.findByDiagramId(UUID.randomUUID(), 0, 20)).isEmpty();
        SqlCapture.statements.remove();
    }


    @Test
    void databasePagesAreCompleteOrderedAndDoNotReadSnapshots() {
        UUID id = newDiagramId();
        for (int n = 1; n <= 45; n++) {
            versionRepository.save(new DiagramVersion(UUID.randomUUID(), id, n, DATA,
                    n == 1 ? null : "note " + n, null, Instant.now().truncatedTo(ChronoUnit.MICROS)));
        }
        entityManager.clear();
        var numbers = new java.util.ArrayList<Integer>();
        for (int page = 0; page < 3; page++) {
            SqlCapture.statements.get().clear();
            var rows = versionRepository.findByDiagramId(id, page, 20);
            assertThat(rows).hasSize(page == 2 ? 5 : 20);
            numbers.addAll(rows.stream().map(com.diagramio.domain.version.DiagramVersionSummary::versionNumber).toList());
            assertThat(SqlCapture.statements.get()).hasSize(1);
            String sql = SqlCapture.statements.get().getFirst().toLowerCase(java.util.Locale.ROOT);
            assertThat(sql).contains("from diagram_versions", "fetch first");
            if (page > 0) assertThat(sql).contains("offset");
            assertThat(sql.substring(0, sql.indexOf(" from "))).doesNotContain(".data", "*");
        }
        assertThat(numbers).containsExactlyElementsOf(java.util.stream.IntStream.iterate(45, n -> n - 1).limit(45).boxed().toList());
        assertThat(versionRepository.findByDiagramId(id, 3, 20)).isEmpty();
        SqlCapture.statements.get().clear();
        assertThat(versionRepository.countByDiagramId(id)).isEqualTo(45);
        assertThat(SqlCapture.statements.get()).hasSize(1);
        assertThat(SqlCapture.statements.get().getFirst().toLowerCase(java.util.Locale.ROOT)).contains("count(").doesNotContain(".data", "fetch first");
        assertThat(versionRepository.countByDiagramId(UUID.randomUUID())).isZero();
        SqlCapture.statements.remove();
    }

}
