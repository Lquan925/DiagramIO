package com.diagramio.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.zip.GZIPInputStream;

/** Raw bytes: java.net.http does not silently decode gzip like many HTTP clients. */
abstract class CompressionHttpTestBase extends E2eTestBase {
    private static final HttpClient RAW = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    protected HttpResponse<byte[]> rawGet(String path, String encoding, Actor actor) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(60)).header("Accept-Encoding", encoding);
        if (actor != null && actor.accessToken() != null) request.header("Authorization", "Bearer " + actor.accessToken());
        if (actor != null && actor.shareToken() != null) request.header("X-Share-Token", actor.shareToken());
        return RAW.send(request.GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    protected byte[] decoded(HttpResponse<byte[]> response) throws Exception {
        if (response.headers().firstValue("Content-Encoding").orElse("").equals("gzip")) {
            try (var stream = new GZIPInputStream(new ByteArrayInputStream(response.body()))) {
                return stream.readAllBytes();
            }
        }
        return response.body();
    }

    protected JsonNode json(HttpResponse<byte[]> response) throws Exception {
        return JSON.readTree(decoded(response));
    }

    protected void assertVary(HttpResponse<byte[]> response) {
        assertThat(response.headers().allValues("Vary").stream()
                .flatMap(value -> java.util.Arrays.stream(value.split(",")))
                .map(String::trim).anyMatch(value -> value.equalsIgnoreCase("Accept-Encoding"))).isTrue();
    }

    protected String largeDiagram(Actor owner) {
        String id = createDiagramId(owner);
        var data = JSON.createObjectNode();
        data.putArray("nodes").addObject().put("id", "n1").put("label", "SÆ¡ Ä‘á»“ tiáº¿ng Viá»‡t ".repeat(12000));
        data.putArray("edges");
        assertStatus(updateDiagram(owner, id, "Large JSON", "UML", getDiagram(owner, id).path("version").asInt(), data, false), 200);
        return id;
    }
}
