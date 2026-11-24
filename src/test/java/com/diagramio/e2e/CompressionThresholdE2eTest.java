package com.diagramio.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = {"server.compression.enabled=true", "server.compression.min-response-size=4KB"})
@Import(CompressionE2eTest.Fixtures.class)
class CompressionThresholdE2eTest extends CompressionHttpTestBase {
    @Test
    void configuredFourKiBThresholdOverridesDefaultForKnownLengthJson() throws Exception {
        Actor owner = registerAndLogin();
        for (int bytes : new int[]{3000, 4096}) {
            var response = rawGet(API + "/compression-test/" + bytes, "gzip", owner);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(decoded(response)).hasSize(bytes);
            if (bytes < 4096) {
                assertThat(response.headers().firstValue("Content-Encoding")).isEmpty();
            } else {
                assertThat(response.headers().firstValue("Content-Encoding")).contains("gzip");
                assertVary(response);
            }
        }
    }
}
