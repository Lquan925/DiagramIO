package com.diagramio.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.diagramio.infrastructure.cache.ReadCacheProperties;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityManagerFactory;
import java.util.Arrays;
import java.util.Locale;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=OFF",
        "cache.diagram-ttl=10m",
        "cache.share-ttl=10m"
})
class ReadCacheBenchmarkTest extends E2eTestBase {

    private static final int WARMUP = 40;
    private static final int SAMPLES = 200;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private ReadCacheProperties properties;

    @Test
    void measureRepeatedHttpReadsAndDatabaseStatements() {
        Actor owner = registerAndLogin();
        String id = createDiagramId(owner);
        setData(owner, id, obj("nodes", new Object[]{obj("id", "n1", "label", "x".repeat(64 * 1024))},
                "edges", new Object[]{}));
        String editor = shareToken(owner, id, "edit");
        String viewer = shareToken(owner, id, "view");
        setPublic(owner, id, true);
        String path = API + "/diagrams/" + id;
        measure("owner", path, owner, 1);
        measure("editor", path, Actor.shareOnly(editor), 2);
        measure("viewer", path, Actor.shareOnly(viewer), 2);
        measure("anonymous", path, Actor.anonymous(), 1);
        measure("share-url", API + "/shares/" + viewer, Actor.anonymous(), 2);
    }

    private void measure(String scenario, String path, Actor actor, int statementsPerRead) {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        for (int i = 0; i < WARMUP; i++) {
            assertStatus(get(path).as(actor).send(), 200);
        }
        statistics.clear();
        long[] nanos = new long[SAMPLES];
        for (int i = 0; i < SAMPLES; i++) {
            long start = System.nanoTime();
            Res response = get(path).as(actor).send();
            nanos[i] = System.nanoTime() - start;
            assertStatus(response, 200);
            JsonNode data = response.json().path("data");
            assertThat(data.path("nodes").get(0).path("label").asText()).hasSize(64 * 1024);
        }
        long queries = statistics.getPrepareStatementCount();
        long expected = properties.mode() == ReadCacheProperties.Mode.LOCAL ? 0L : (long) SAMPLES * statementsPerRead;
        assertThat(queries).as("SQL statements for %s in %s mode", scenario, properties.mode()).isEqualTo(expected);
        Arrays.sort(nanos);
        System.out.printf(Locale.ROOT,
                "CACHE_BENCH mode=%s scenario=%s samples=%d sql=%d mean_ms=%.3f p50_ms=%.3f p95_ms=%.3f%n",
                properties.mode(), scenario, SAMPLES, queries,
                Arrays.stream(nanos).average().orElseThrow() / 1_000_000.0,
                nanos[SAMPLES / 2] / 1_000_000.0, nanos[(int) Math.ceil(SAMPLES * 0.95) - 1] / 1_000_000.0);
    }
}
