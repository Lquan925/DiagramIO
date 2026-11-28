package com.diagramio.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.diagramio.domain.access.Caller;
import com.diagramio.domain.diagram.Diagram;
import com.diagramio.domain.diagram.DiagramData;
import com.diagramio.domain.diagram.DiagramType;
import com.diagramio.domain.version.DiagramVersionService;
import com.diagramio.infrastructure.diagram.DiagramRepositoryAdapter;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real transaction + flushed backup, with a controlled failure at the second write. */
@SpringBootTest(properties = "cache.mode=local")
@Testcontainers(disabledWithoutDocker = true)
class RestoreAtomicityIntegrationTest {
    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");
    @Autowired DiagramVersionService versions;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean DiagramRepositoryAdapter diagrams;
    static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final Caller OWNER = Caller.user(USER);

    @ParameterizedTest(name = "failure after diagram flush = {0}")
    @ValueSource(booleans = {false, true})
    void failedRestoreRollsBackBackupAndDiagramWithoutPublishingUncommittedData(boolean afterFlush) {
        var oldData = DiagramData.of("{\"nodes\":[{\"id\":\"OLD\"}],\"edges\":[]}");
        var currentData = DiagramData.of("{\"nodes\":[{\"id\":\"CURRENT\"}],\"edges\":[]}");
        Instant now = Instant.now();
        Diagram d = diagrams.save(new Diagram(null, "rollback", DiagramType.UML, oldData,
                1, false, USER, USER, now, now));
        var target = versions.createVersion(d.getId(), "target", null, OWNER);
        d.setData(currentData);
        diagrams.save(d);
        Diagram before = diagrams.findById(d.getId()).orElseThrow();
        var storedTarget = versions.getVersion(d.getId(), target.id(), OWNER);
        // Warm cache when this test runs in the separate integration checkout with Manh.
        diagrams.findById(d.getId());
        diagrams.findById(d.getId());
        try (var reader = Executors.newSingleThreadExecutor()) {
            doAnswer(call -> {
                // Backup is genuinely present in this transaction before injecting failure.
                assertThat(jdbc.queryForObject("select count(*) from diagram_versions where diagram_id = ?",
                        Integer.class, d.getId())).isEqualTo(2);
                if (afterFlush) {
                    call.callRealMethod();
                    assertThat(jdbc.queryForObject("select data::text from diagrams where id = ?",
                            String.class, d.getId())).contains("OLD");
                }
                Diagram concurrent = reader.submit(() -> diagrams.findById(d.getId()).orElseThrow()).get(10, TimeUnit.SECONDS);
                assertThat(concurrent.getData().json()).contains("CURRENT");
                assertThat(concurrent.getVersion()).isEqualTo(before.getVersion());
                throw new RuntimeException("injected restore write failure");
            }).when(diagrams).save(any(Diagram.class));
            assertThatThrownBy(() -> versions.restore(d.getId(), target.id(), OWNER))
                    .isInstanceOf(RuntimeException.class).hasMessage("injected restore write failure");
        }
        assertThat(jdbc.queryForObject("select count(*) from diagram_versions where diagram_id = ?",
                Integer.class, d.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select data::text from diagrams where id = ?",
                String.class, d.getId())).contains("CURRENT");
        Diagram after = diagrams.findById(d.getId()).orElseThrow();
        assertThat(after.getVersion()).isEqualTo(before.getVersion());
        assertThat(after.getUpdatedAt()).isEqualTo(before.getUpdatedAt());
        assertThat(after.getUpdatedBy()).isEqualTo(before.getUpdatedBy());
        assertThat(after.getData().json()).contains("CURRENT");
        assertThat(versions.getVersion(d.getId(), target.id(), OWNER)).isEqualTo(storedTarget);
    }
}
