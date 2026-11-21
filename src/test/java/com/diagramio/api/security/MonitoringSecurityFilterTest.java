package com.diagramio.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.diagramio.api.dto.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class MonitoringSecurityFilterTest {

    private static final String SECRET_TOKEN = "test-monitoring-secret-key-123456";
    private ObjectMapper objectMapper;
    private FilterChain filterChain;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        filterChain = mock(FilterChain.class);
    }

    @Test
    void healthEndpoint_isPubliclyAccessibleFromAnyIpWithoutToken() throws ServletException, IOException {
        MonitoringSecurityFilter filter = new MonitoringSecurityFilter(SECRET_TOKEN, false, objectMapper);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        request.setRemoteAddr("203.0.113.10"); // External IP
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void prometheusEndpoint_blockedWhenExternalIpAndNoToken() throws ServletException, IOException {
        MonitoringSecurityFilter filter = new MonitoringSecurityFilter(SECRET_TOKEN, true, objectMapper);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/prometheus");
        request.setRemoteAddr("203.0.113.10"); // External IP
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain, never()).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(403);

        ErrorResponse errorResponse = objectMapper.readValue(response.getContentAsString(), ErrorResponse.class);
        assertThat(errorResponse.status()).isEqualTo(403);
        assertThat(errorResponse.message()).contains("Access denied");
    }

    @Test
    void prometheusEndpoint_allowedWithXMonitoringTokenHeader() throws ServletException, IOException {
        MonitoringSecurityFilter filter = new MonitoringSecurityFilter(SECRET_TOKEN, false, objectMapper);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/prometheus");
        request.setRemoteAddr("203.0.113.10");
        request.addHeader("X-Monitoring-Token", SECRET_TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    @Test
    void prometheusEndpoint_allowedWithBearerToken() throws ServletException, IOException {
        MonitoringSecurityFilter filter = new MonitoringSecurityFilter(SECRET_TOKEN, false, objectMapper);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/prometheus");
        request.setRemoteAddr("203.0.113.10");
        request.addHeader("Authorization", "Bearer " + SECRET_TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    @Test
    void prometheusEndpoint_allowedFromLocalhostWhenAllowLocalIsTrue() throws ServletException, IOException {
        MonitoringSecurityFilter filter = new MonitoringSecurityFilter(SECRET_TOKEN, true, objectMapper);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/prometheus");
        request.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    @Test
    void prometheusEndpoint_blockedFromLocalhostWhenAllowLocalIsFalseAndNoToken() throws ServletException, IOException {
        MonitoringSecurityFilter filter = new MonitoringSecurityFilter(SECRET_TOKEN, false, objectMapper);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/prometheus");
        request.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain, never()).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void nonActuatorEndpoints_passthroughDirectly() throws ServletException, IOException {
        MonitoringSecurityFilter filter = new MonitoringSecurityFilter(SECRET_TOKEN, false, objectMapper);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/diagrams");
        request.setRemoteAddr("203.0.113.10");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }
}
