package com.diagramio.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class ShareExpiryMigrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Test
    void upgradingFromV3PreservesExistingLinkWithoutAnExpiry() {
        FluentConfiguration migrations = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        migrations.target("3").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        UUID diagramId = UUID.randomUUID();
        UUID shareId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2020-01-01T00:00:00Z");
        jdbc.update("insert into diagrams (id, title, diagram_type, owner_id) values (?, ?, ?, ?)",
                diagramId, "Legacy diagram", "UML", UUID.fromString("11111111-1111-1111-1111-111111111111"));
        jdbc.update("insert into diagram_shares (id, diagram_id, share_token, role, created_at) values (?, ?, ?, ?, ?)",
                shareId, diagramId, "legacy-before-v4", "edit", Timestamp.from(createdAt));

        assertThat(migrations.target("4").load().migrate().migrationsExecuted).isEqualTo(1);

        var row = jdbc.queryForMap("select * from diagram_shares where id = ?", shareId);
        assertThat(row.get("diagram_id")).isEqualTo(diagramId);
        assertThat(row.get("share_token")).isEqualTo("legacy-before-v4");
        assertThat(row.get("role")).isEqualTo("edit");
        assertThat(((Timestamp) row.get("created_at")).toInstant()).isEqualTo(createdAt);
        assertThat(row).containsKey("expires_at").containsEntry("expires_at", null);
    }
}
