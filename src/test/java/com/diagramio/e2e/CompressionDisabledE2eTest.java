package com.diagramio.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "server.compression.enabled=false")
class CompressionDisabledE2eTest extends CompressionHttpTestBase {
    @Test
    void disablingCompressionReturnsIdentityEvenWhenClientAcceptsGzip() throws Exception {
        Actor owner = registerAndLogin();
        String path = API + "/diagrams/" + largeDiagram(owner);
        var gzipRequested = rawGet(path, "gzip", owner);
        var identity = rawGet(path, "identity", owner);
        assertThat(gzipRequested.statusCode()).isEqualTo(200);
        assertThat(gzipRequested.headers().firstValue("Content-Encoding")).isEmpty();
        assertThat(gzipRequested.body()).isEqualTo(identity.body());
        assertThat(json(gzipRequested).path("data").isObject()).isTrue();
    }
}
