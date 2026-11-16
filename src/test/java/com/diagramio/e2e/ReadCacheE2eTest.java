package com.diagramio.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "cache.mode=local")
@Import(ReadCacheE2eTest.ClockConfiguration.class)
class ReadCacheE2eTest extends E2eTestBase {

    @Autowired
    private AdjustableClock clock;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void resetClock() {
        clock.now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }

    @Test
    void warmCacheStillChecksEveryCallerAndReturnsFreshContentAfterEdit() {
        Actor owner = registerAndLogin();
        Actor other = registerAndLogin();
        String id = createDiagramId(owner);
        Actor editor = other.withShare(shareToken(owner, id, "edit"));
        Actor viewer = other.withShare(shareToken(owner, id, "view"));
        getDiagram(owner, id);
        getDiagram(owner, id);
        assertStatus(get(API + "/diagrams/" + id).as(editor).send(), 200);
        assertStatus(get(API + "/diagrams/" + id).as(viewer).send(), 200);
        assertError(get(API + "/diagrams/" + id).send(), 401);
        assertError(get(API + "/diagrams/" + id).as(other).send(), 403);
        assertStatus(get(API + "/diagrams/" + id + "/shares").as(owner).send(), 200);
        assertError(post(API + "/diagrams/" + id + "/shares").as(editor).json(obj("role", "view")).send(), 403);
        assertError(delete(API + "/diagrams/" + id).as(viewer).send(), 403);

        JsonNode current = getDiagram(owner, id);
        Res updated = updateDiagram(editor, id, "Edited", "UML", current.path("version").asInt(),
                dataWith("new"), true);
        assertStatus(updated, 200);
        assertThat(updated.json().path("isPublic").asBoolean()).isFalse();
        JsonNode fresh = getDiagram(viewer, id);
        assertThat(fresh.path("title").asText()).isEqualTo("Edited");
        assertThat(fresh.path("data")).isEqualTo(dataWith("new"));
        assertError(updateDiagram(viewer, id, "Denied", "UML", fresh.path("version").asInt(),
                dataWith("denied"), false), 403);
        assertError(updateDiagram(owner, id, "Stale", "UML", current.path("version").asInt(),
                dataWith("stale"), true), 409);
        assertThat(getDiagram(owner, id).path("data")).isEqualTo(dataWith("new"));
        assertError(get(API + "/diagrams/" + createDiagramId(owner)).as(editor).send(), 403);
    }

    @Test
    void privatizingAndDeletingEvictWarmDiagramAndCascadedTokens() {
        Actor owner = registerAndLogin();
        String id = createDiagramId(owner);
        String token = shareToken(owner, id, "view");
        setPublic(owner, id, true);
        assertStatus(get(API + "/diagrams/" + id).send(), 200);
        assertStatus(get(API + "/shares/" + token).send(), 200);
        setPublic(owner, id, false);
        assertError(get(API + "/diagrams/" + id).send(), 401);
        assertStatus(get(API + "/diagrams/" + id).share(token).send(), 200);
        assertStatus(delete(API + "/diagrams/" + id).as(owner).send(), 204);
        assertError(get(API + "/diagrams/" + id).as(owner).send(), 404);
        assertError(get(API + "/diagrams/" + id).share(token).send(), 404);
        assertError(get(API + "/shares/" + token).send(), 404);
    }

    @Test
    void roleChangesAndRevocationTakeEffectOnNextRequestWithWarmTokens() {
        Actor owner = registerAndLogin();
        String id = createDiagramId(owner);
        JsonNode share = createShare(owner, id, "edit");
        String token = share.path("shareToken").asText();
        String sharePath = API + "/diagrams/" + id + "/shares/" + share.path("id").asText();
        Actor link = Actor.shareOnly(token);
        getDiagram(link, id);
        assertStatus(get(API + "/shares/" + token).send(), 200);
        Res downgrade = patch(sharePath).as(owner).json(obj("role", "view")).send();
        assertStatus(downgrade, 200);
        assertThat(downgrade.json().path("expiresAt")).isEqualTo(share.path("expiresAt"));
        JsonNode current = getDiagram(link, id);
        assertError(updateDiagram(link, id, "Denied", "UML", current.path("version").asInt(),
                dataWith("denied"), false), 403);
        assertThat(get(API + "/shares/" + token).send().json().path("role").asText()).isEqualTo("view");
        assertStatus(patch(sharePath).as(owner).json(obj("role", "edit")).send(), 200);
        assertStatus(updateDiagram(link, id, "Allowed", "UML", current.path("version").asInt(),
                dataWith("allowed"), false), 200);
        getDiagram(link, id);
        assertStatus(delete(sharePath).as(owner).send(), 204);
        assertError(get(API + "/diagrams/" + id).share(token).send(), 401);
        assertError(get(API + "/shares/" + token).send(), 404);
        assertStatus(get(API + "/diagrams/" + id).as(owner).share(token).send(), 200);
    }

    @Test
    void expiryInvalidatesBothTokenPathsWithoutRemovingIndependentOwnerOrPublicAccess() {
        Actor owner = registerAndLogin();
        Actor other = registerAndLogin();
        String id = createDiagramId(owner);
        Instant expiry = clock.instant().plusSeconds(60);
        Res created = post(API + "/diagrams/" + id + "/shares").as(owner)
                .json(obj("role", "edit", "expiresAt", expiry.toString())).send();
        assertStatus(created, 201);
        String token = created.json().path("shareToken").asText();
        String sharePath = API + "/diagrams/" + id + "/shares/" + created.json().path("id").asText();
        assertStatus(get(API + "/diagrams/" + id).share(token).send(), 200);
        assertStatus(get(API + "/shares/" + token).send(), 200);
        clock.now = expiry;
        assertError(get(API + "/diagrams/" + id).share(token).send(), 401);
        assertError(get(API + "/diagrams/" + id).as(other).share(token).send(), 403);
        assertError(get(API + "/shares/" + token).send(), 404);
        assertStatus(get(API + "/diagrams/" + id).as(owner).share(token).send(), 200);
        Res changed = patch(sharePath).as(owner).json(obj("role", "view")).send();
        assertStatus(changed, 200);
        assertThat(Instant.parse(changed.json().path("expiresAt").asText())).isEqualTo(expiry);
        assertError(get(API + "/shares/" + token).send(), 404);
        setPublic(owner, id, true);
        assertStatus(get(API + "/diagrams/" + id).share(token).send(), 200);
        assertError(get(API + "/shares/" + token).send(), 404);
        assertStatus(delete(sharePath).as(owner).send(), 204);
    }

    @Test
    void legacyLinksRemainUsableWhileNewLinksHaveFiniteValidatedExpiry() {
        Actor owner = registerAndLogin();
        String id = createDiagramId(owner);
        JsonNode share = createShare(owner, id, "view");
        assertThat(Instant.parse(share.path("expiresAt").asText()))
                .isBetween(clock.instant().plusSeconds(7 * 86400 - 1), clock.instant().plusSeconds(7 * 86400 + 1));
        assertError(post(API + "/diagrams/" + id + "/shares").as(owner)
                .json(obj("role", "view", "expiresAt", clock.instant().toString())).send(), 400);
        assertError(post(API + "/diagrams/" + id + "/shares").as(owner)
                .json(obj("role", "view", "expiresAt", "not-a-date")).send(), 400);
        jdbc.update("update diagram_shares set expires_at = null where id = ?",
                java.util.UUID.fromString(share.path("id").asText()));
        assertThat(get(API + "/diagrams/" + id + "/shares").as(owner).send().json().get(0).path("expiresAt").isNull()).isTrue();
        String token = share.path("shareToken").asText();
        assertStatus(get(API + "/shares/" + token).send(), 200);
        clock.now = clock.instant().plusSeconds(8 * 86400);
        assertStatus(get(API + "/shares/" + token).send(), 200);
        assertStatus(get(API + "/diagrams/" + id).share(token).send(), 200);
    }

    @TestConfiguration
    static class ClockConfiguration {
        @Bean
        @Primary
        AdjustableClock adjustableClock() {
            return new AdjustableClock();
        }
    }

    static final class AdjustableClock extends Clock {
        private volatile Instant now = Instant.now();

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(now, zone);
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
