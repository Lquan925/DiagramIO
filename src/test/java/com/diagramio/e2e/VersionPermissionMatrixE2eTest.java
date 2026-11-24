package com.diagramio.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Verify each identity through HTTP, including denied writes leaving no backup. */
class VersionPermissionMatrixE2eTest extends E2eTestBase {
    @ParameterizedTest(name = "{0}: history={1}, write={2}")
    @CsvSource({"owner,200,201", "owner-view,200,201", "guest-edit,200,201",
            "user-edit,200,201", "guest-view,200,403", "user-view,200,403",
            "other,403,403", "anonymous,401,401", "revoked-guest,401,401",
            "revoked-user,403,403", "wrong-guest,401,401", "wrong-user,403,403"})
    void permissionsAndFinalState(String mode, int readStatus, int writeStatus) {
        Actor owner = registerAndLogin();
        Actor other = registerAndLogin();
        String id = createDiagramId(owner);
        setData(owner, id, dataWith("SNAPSHOT"));
        var target = createVersion(owner, id, "immutable");
        setData(owner, id, dataWith("CURRENT"));
        var current = getDiagram(owner, id);
        String base = API + "/diagrams/" + id + "/versions";
        String detail = base + "/" + target.path("id").asText();
        var storedTarget = get(detail).as(owner).send().json();
        Actor caller = switch (mode) {
            case "owner", "owner-view" -> owner;
            case "other", "user-edit", "user-view", "revoked-user", "wrong-user" -> other;
            default -> Actor.anonymous();
        };
        if (mode.contains("edit") || mode.contains("view") || mode.startsWith("revoked") || mode.startsWith("wrong")) {
            String shareDiagram = mode.startsWith("wrong") ? createDiagramId(owner) : id;
            var share = createShare(owner, shareDiagram, mode.endsWith("view") ? "view" : "edit");
            caller = caller.withShare(share.path("shareToken").asText());
            if (mode.startsWith("revoked")) {
                assertStatus(get(base).as(caller).send(), 200); // warm token before revocation
                assertStatus(delete(API + "/diagrams/" + id + "/shares/" + share.path("id").asText()).as(owner).send(), 204);
            }
        }
        for (String query : new String[]{"", "?page=0&size=1", "?page=9&size=100"}) {
            Res response = get(base + query).as(caller).send();
            if (readStatus == 200) {
                assertStatus(response, 200);
                assertThat(response.json().path("totalElements").asInt()).isEqualTo(1);
                assertThat(items(response.json().path("content"))).hasSize(query.contains("page=9") ? 0 : 1);
                response.json().path("content").forEach(v -> assertThat(v.has("data")).isFalse());
            } else {
                assertError(response, readStatus);
                assertThat(response.body()).doesNotContain("SNAPSHOT", "immutable");
            }
        }
        Res read = get(detail).as(caller).send();
        if (readStatus == 200) {
            assertStatus(read, 200);
            assertThat(read.json().path("data")).isEqualTo(dataWith("SNAPSHOT"));
            assertError(get(base + "?size=101").as(caller).send(), 400);
        } else assertError(read, readStatus);
        Res snapshot = post(base).as(caller).json(obj("note", "new")).send();
        Res restore = post(detail + "/restore").as(caller).send();
        if (writeStatus == 201) {
            assertStatus(snapshot, 201);
            assertThat(snapshot.json().path("data")).isEqualTo(dataWith("CURRENT"));
            assertStatus(restore, 200);
            var after = getDiagram(owner, id);
            assertThat(after.path("data")).isEqualTo(dataWith("SNAPSHOT"));
            assertThat(after.path("version").asInt()).isEqualTo(current.path("version").asInt() + 1);
            assertThat(snapshot.json().path("createdBy")).isEqualTo(JSON.valueToTree(caller.userId()));
            assertThat(get(base).as(owner).send().json().path("totalElements").asInt()).isEqualTo(3);
        } else {
            assertError(snapshot, writeStatus);
            assertError(restore, writeStatus);
            assertThat(getDiagram(owner, id)).isEqualTo(current);
            assertThat(get(base).as(owner).send().json().path("totalElements").asInt()).isEqualTo(1);
        }
        assertThat(get(detail).as(owner).send().json()).isEqualTo(storedTarget);
    }
}
