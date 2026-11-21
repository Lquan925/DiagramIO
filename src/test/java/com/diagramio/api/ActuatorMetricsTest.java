package com.diagramio.api;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.diagramio.infrastructure.security.SampleAccountSecurityManager;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "spring.jpa.hibernate.ddl-auto=none",
        "management.health.db.enabled=false"
})
@AutoConfigureMockMvc
@Import(ActuatorMetricsTest.TestConfig.class)
class ActuatorMetricsTest {

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        public DataSource dataSource() throws SQLException {
            DataSource ds = mock(DataSource.class);
            Connection conn = mock(Connection.class);
            DatabaseMetaData meta = mock(DatabaseMetaData.class);
            when(ds.getConnection()).thenReturn(conn);
            when(conn.getMetaData()).thenReturn(meta);
            when(meta.getDatabaseProductName()).thenReturn("PostgreSQL");
            when(meta.getDatabaseMajorVersion()).thenReturn(15);
            when(meta.getDatabaseMinorVersion()).thenReturn(0);
            return ds;
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private Flyway flyway;

    @MockitoBean
    private SampleAccountSecurityManager sampleAccountSecurityManager;

    @Test
    void healthEndpoint_isUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void prometheusEndpoint_accessibleWithMonitoringToken() throws Exception {
        mockMvc.perform(get("/actuator/prometheus")
                        .header("X-Monitoring-Token", "monitoring-secret-token-32chars-ok"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("jvm_")));
    }

    @Test
    void prometheusEndpoint_forbiddenForExternalIpWithoutToken() throws Exception {
        mockMvc.perform(get("/actuator/prometheus")
                        .with(request -> {
                            request.setRemoteAddr("198.51.100.5");
                            return request;
                        }))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value("Access denied: monitoring endpoints are restricted."));
    }
}
