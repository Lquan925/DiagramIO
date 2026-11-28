package com.diagramio.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@TestPropertySource(properties = {"server.compression.enabled=true", "server.compression.min-response-size=2KB"})
@Import(CompressionE2eTest.Fixtures.class)
class CompressionE2eTest extends CompressionHttpTestBase {
    @Test
    void largeDiagramAndVersionNegotiateGzipWithoutChangingJsonOrPermissions() throws Exception {
        Actor owner = registerAndLogin();
        String id = largeDiagram(owner);
        String token = shareToken(owner, id, "view");
        var version = post(API + "/diagrams/" + id + "/versions").bearer(owner.accessToken())
                .json(obj("note", "Unicode snapshot")).send();
        assertStatus(version, 201);
        String versionPath = API + "/diagrams/" + id + "/versions/" + version.json().path("id").asText();
        for (String path : new String[]{API + "/diagrams/" + id, versionPath}) {
            var identity = rawGet(path, "identity", owner);
            var gzip = rawGet(path, "gzip", owner);
            assertThat(identity.statusCode()).isEqualTo(200);
            assertThat(gzip.statusCode()).isEqualTo(200);
            assertThat(identity.headers().firstValue("Content-Encoding")).isEmpty();
            assertThat(gzip.headers().firstValue("Content-Encoding")).contains("gzip");
            assertVary(identity);
            assertVary(gzip);
            assertThat(gzip.body().length).isLessThan(identity.body().length);
            assertThat(decoded(gzip)).isEqualTo(identity.body());
            assertThat(json(gzip).path("data").isObject()).isTrue();
            assertThat(json(gzip).path("data")).isEqualTo(getDiagram(owner, id).path("data"));
            assertThat(json(rawGet(path, "gzip", Actor.shareOnly(token))).path("data")).isEqualTo(json(identity).path("data"));
            for (String encoding : new String[]{"gzip", "identity"}) {
                var denied = rawGet(path, encoding, null);
                assertThat(denied.statusCode()).isEqualTo(401);
                assertThat(json(denied).path("status").asInt()).isEqualTo(401);
                assertThat(json(denied).has("data")).isFalse();
                // Public-readable diagram URLs reach domain/MVC; version URLs are rejected by security.
                if (encoding.equals("gzip") && !path.equals(versionPath)) {
                    assertThat(denied.headers().firstValue("Content-Encoding")).contains("gzip");
                    assertVary(denied);
                } else {
                    assertThat(denied.headers().firstValue("Content-Encoding")).isEmpty();
                }
                assertThat(decoded(denied).length).isLessThan(2048);
            }
        }
        Actor stranger = registerAndLogin();
        assertThat(rawGet(versionPath, "gzip", stranger).statusCode()).isEqualTo(403);
        var snapshotData = json(rawGet(versionPath, "gzip", owner)).path("data");
        assertStatus(updateDiagram(owner, id, "Changed", "UML", getDiagram(owner, id).path("version").asInt(), emptyData(), false), 200);
        var restored = post(versionPath + "/restore").bearer(owner.accessToken()).send();
        assertStatus(restored, 200);
        assertThat(restored.json().path("data")).isEqualTo(snapshotData);
        assertThat(json(rawGet(API + "/diagrams/" + id, "gzip", owner)).path("data")).isEqualTo(snapshotData);

    }

    @Test
    void knownLengthJsonHonoursTwoKiBThreshold() throws Exception {
        Actor owner = registerAndLogin();
        for (int bytes : new int[]{2047, 2048, 2049}) {
            var response = rawGet(API + "/compression-test/" + bytes, "gzip", owner);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(decoded(response)).hasSize(bytes);
            if (bytes < 2048) {
                assertThat(response.headers().firstValue("Content-Encoding")).isEmpty();
            } else {
                assertThat(response.headers().firstValue("Content-Encoding")).contains("gzip");
                assertVary(response);
            }
        }
    }

    @Test
    void smallStreamingJsonAndErrorsRemainReadable() throws Exception {
        Actor owner = registerAndLogin();
        String id = createDiagramId(owner);
        for (String encoding : new String[]{"gzip", "identity"}) {
            var response = rawGet(API + "/diagrams/" + id, encoding, owner);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(json(response).path("data").isObject()).isTrue();
            assertThat(decoded(response).length).isLessThan(2048);
            // Tomcat compresses unknown-length streaming JSON even below min-response-size.
            if (encoding.equals("gzip")) {
                assertThat(response.headers().firstValue("Content-Encoding")).contains("gzip");
                assertVary(response);
            } else {
                assertThat(response.headers().firstValue("Content-Encoding")).isEmpty();
            }
            var error = rawGet(API + "/diagrams/not-a-uuid", encoding, owner);
            assertThat(error.statusCode()).isEqualTo(400);
            assertThat(json(error).path("status").asInt()).isEqualTo(400);
            assertThat(error.headers().firstValue("Content-Encoding"))
                    .isEqualTo(encoding.equals("gzip") ? java.util.Optional.of("gzip") : java.util.Optional.empty());
            assertVary(error);
        }
    }

    @TestConfiguration
    static class Fixtures {
        @Bean KnownLengthJson knownLengthJson() { return new KnownLengthJson(); }
    }

    @RestController
    static class KnownLengthJson {
        @GetMapping("/api/v1/compression-test/{bytes}")
        ResponseEntity<byte[]> json(@PathVariable int bytes) {
            byte[] body = ("{\"padding\":\"" + "x".repeat(bytes - 14) + "\"}").getBytes(StandardCharsets.UTF_8);
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).contentLength(body.length).body(body);
        }
    }
}
