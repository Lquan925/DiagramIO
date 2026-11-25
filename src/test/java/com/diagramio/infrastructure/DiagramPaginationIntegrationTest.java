package com.diagramio.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.diagramio.domain.diagram.Diagram;
import com.diagramio.domain.diagram.DiagramPage;
import com.diagramio.domain.diagram.DiagramType;
import com.diagramio.infrastructure.diagram.DiagramMapper;
import com.diagramio.infrastructure.diagram.DiagramRepositoryAdapter;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/** Real PostgreSQL; optionally supply diagramio.test.jdbc-url and username/password. */
@DataJpaTest(properties = "spring.jpa.show-sql=false")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({DiagramRepositoryAdapter.class, DiagramMapper.class})
@EnabledIf("databaseAvailable")
class DiagramPaginationIntegrationTest {
    private static final UUID OWNER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER = new UUID(0, 999);
    private static final String SCHEMA = "pagination_test_" + UUID.randomUUID().toString().replace("-", "");
    private static PostgreSQLContainer<?> postgres;
    private static String jdbcUrl;
    private static String username;
    private static String password;

    @Autowired private DiagramRepositoryAdapter diagrams;
    @Autowired private JdbcTemplate jdbc;

    static boolean databaseAvailable() {
        return System.getProperty("diagramio.test.jdbc-url") != null
                || DockerClientFactory.instance().isDockerAvailable();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        jdbcUrl = System.getProperty("diagramio.test.jdbc-url");
        if (jdbcUrl == null) {
            postgres = new PostgreSQLContainer<>("postgres:15-alpine");
            postgres.start();
            jdbcUrl = postgres.getJdbcUrl();
            username = postgres.getUsername();
            password = postgres.getPassword();
        } else {
            username = System.getProperty("diagramio.test.jdbc-username", "postgres");
            password = System.getProperty("diagramio.test.jdbc-password", "");
        }
        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.username", () -> username);
        registry.add("spring.datasource.password", () -> password);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.session_factory.statement_inspector",
                () -> SqlCapture.class.getName());
    }

    @AfterAll
    static void cleanup() throws Exception {
        try {
            if (jdbcUrl != null) {
                try (var connection = DriverManager.getConnection(jdbcUrl, username, password);
                        var statement = connection.createStatement()) {
                    statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
                }
            }
        } finally {
            if (postgres != null) postgres.stop();
        }
    }

    @BeforeEach
    void seed() {
        jdbc.update("INSERT INTO " + SCHEMA + ".users (id, username, email, password_hash) VALUES (?, ?, ?, ?)",
                OTHER, "other", "other@example.test", "test-hash");
        insert(1, "Orders ERD", "ERD", false, OWNER);
        insert(2, "Orders UML", "UML", true, OWNER);
        insert(3, "Inventory", "ERD", true, OWNER);
        insert(4, "Orders from another owner", "ERD", true, OTHER);
        SqlCapture.clear();
    }

    @Test
    void filtersAndOwnerIsolationShareOneQueryAndOneTotal() {
        assertOwner(null, "", 3, 2, 1);
        assertOwner(DiagramType.ERD, "", 3, 1);
        assertOwner(null, "oRdErS", 2, 1);
        assertOwner(DiagramType.ERD, "oRdErS", 1);
        assertOwner(DiagramType.UML, "inventory");
        assertOwner(null, "missing");
        SqlCapture.clear();
        assertThat(diagrams.findByOwnerId(OWNER, null, "", 0, 2).content().getFirst()
                .getData().json()).contains("nodes");
        assertOneQuery();
    }

    @Test
    void pagesHaveStableOrderAndEmptyPageRetainsTotal() {
        for (int page = 0; page < 3; page++) {
            SqlCapture.clear();
            DiagramPage result = diagrams.findByOwnerId(OWNER, null, "", page, 2);
            assertThat(result.totalElements()).isEqualTo(3);
            if (page == 0) assertIds(result, 3, 2);
            if (page == 1) assertIds(result, 1);
            if (page == 2) assertIds(result);
            assertOneQuery();
        }
        SqlCapture.clear();
        DiagramPage deepPage = diagrams.findByOwnerId(OWNER, null, "", Integer.MAX_VALUE, 100);
        assertIds(deepPage);
        assertThat(deepPage.totalElements()).isEqualTo(3);
        assertOneQuery();
    }

    @Test
    void publicListCountsOnlyPublicDiagramsIncludingEmptyPages() {
        for (int page = 0; page < 3; page++) {
            SqlCapture.clear();
            DiagramPage result = diagrams.findPublicDiagrams(page, 2);
            assertThat(result.totalElements()).isEqualTo(3);
            if (page == 0) assertIds(result, 4, 3);
            if (page == 1) assertIds(result, 2);
            if (page == 2) assertIds(result);
            assertOneQuery();
        }
        jdbc.update("UPDATE " + SCHEMA + ".diagrams SET is_public = false");
        SqlCapture.clear();
        DiagramPage empty = diagrams.findPublicDiagrams(0, 20);
        assertIds(empty);
        assertThat(empty.totalElements()).isZero();
        assertOneQuery();
    }

    @Test
    void searchTreatsWildcardsEscapeAndSqlInputLiterally() {
        insert(10, "Growth 100%", "ERD", false, OWNER);
        insert(11, "Column_name", "ERD", false, OWNER);
        insert(12, "Folder\\Diagram", "ERD", false, OWNER);
        insert(13, "Important!", "ERD", false, OWNER);
        assertOwner(null, "%", 10);
        assertOwner(null, "_", 11);
        assertOwner(null, "\\", 12);
        assertOwner(null, "!", 13);
        assertOwner(null, "' OR true --");
    }

    @Test
    void nullableTimestampsArePreserved() {
        jdbc.update("UPDATE " + SCHEMA + ".diagrams SET updated_at = NULL WHERE id = ?", id(1));
        SqlCapture.clear();
        DiagramPage result = diagrams.findByOwnerId(OWNER, null, "", 0, 1);
        assertIds(result, 1);
        assertThat(result.content().getFirst().getUpdatedAt()).isNull();
        assertThat(result.content().getFirst().getCreatedAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        assertOneQuery();
    }

    private void assertOwner(DiagramType type, String title, long... ids) {
        SqlCapture.clear();
        DiagramPage result = diagrams.findByOwnerId(OWNER, type, title, 0, 20);
        assertIds(result, ids);
        assertThat(result.totalElements()).isEqualTo(ids.length);
        assertOneQuery();
    }

    private void assertIds(DiagramPage result, long... ids) {
        assertThat(result.content()).extracting(Diagram::getId)
                .containsExactly(java.util.Arrays.stream(ids).mapToObj(DiagramPaginationIntegrationTest::id)
                        .toArray(UUID[]::new));
    }

    private void assertOneQuery() {
        assertThat(SqlCapture.SQL.get()).hasSize(1);
        assertThat(SqlCapture.SQL.get().getFirst()).startsWith("with filtered")
                .contains("count(*)", "left join", "limit", "offset");
    }

    private void insert(long number, String title, String type, boolean isPublic, UUID owner) {
        jdbc.update("INSERT INTO " + SCHEMA + ".diagrams "
                + "(id, title, diagram_type, is_public, owner_id, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z')",
                id(number), title, type, isPublic, owner);
    }

    private static UUID id(long number) { return new UUID(0, number); }

    public static class SqlCapture implements StatementInspector {
        private static final ThreadLocal<List<String>> SQL = ThreadLocal.withInitial(ArrayList::new);
        @Override public String inspect(String sql) {
            SQL.get().add(sql.toLowerCase(Locale.ROOT));
            return sql;
        }
        static void clear() { SQL.get().clear(); }
    }
}
