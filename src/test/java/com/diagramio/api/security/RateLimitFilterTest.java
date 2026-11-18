package com.diagramio.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RateLimitFilterTest {

    private RateLimiter rateLimiter;
    private ObjectMapper objectMapper;
    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        rateLimiter = mock(RateLimiter.class);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        filter = new RateLimitFilter(rateLimiter, objectMapper);
    }

    @Test
    void allowsRequestsUnderLimit() throws ServletException, IOException {
        when(rateLimiter.tryAcquire(anyString())).thenReturn(RateLimitResult.allow());

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setRemoteAddr("192.168.1.1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
        verify(rateLimiter).tryAcquire("192.168.1.1:/api/v1/auth/login");
    }

    @Test
    void blocksRequestsExceedingLimitWith429AndUnifiedErrorResponse() throws ServletException, IOException {
        when(rateLimiter.tryAcquire("192.168.1.1:/api/v1/auth/login"))
                .thenReturn(RateLimitResult.deny(45));

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setRemoteAddr("192.168.1.1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("45");
        assertThat(response.getContentType()).contains("application/json");
        assertThat(chain.getRequest()).isNull(); // Request bị chặn lại, không đi tiếp vào controller

        JsonNode json = objectMapper.readTree(response.getContentAsString());
        assertThat(json.path("status").asInt()).isEqualTo(429);
        assertThat(json.path("error").asText()).isEqualTo("Too Many Requests");
        assertThat(json.path("message").asText()).isEqualTo("Too many requests. Please try again later.");
        assertThat(json.path("path").asText()).isEqualTo("/api/v1/auth/login");
        assertThat(json.path("timestamp").asText()).isNotBlank();
    }

    @Test
    void ignoresNonProtectedEndpoints() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/health");
        request.setRemoteAddr("192.168.1.1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
        verify(rateLimiter, never()).tryAcquire(anyString());
    }

    @Test
    void ignoresNonPostRequestsOnAuthEndpoints() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/auth/login");
        request.setRemoteAddr("192.168.1.1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        verify(rateLimiter, never()).tryAcquire(anyString());
    }

    @Test
    void extractsClientIpFromXForwardedFor() throws ServletException, IOException {
        when(rateLimiter.tryAcquire(anyString())).thenReturn(RateLimitResult.allow());

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/register");
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "203.0.113.195, 70.41.3.18, 150.172.238.178");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        verify(rateLimiter).tryAcquire("203.0.113.195:/api/v1/auth/register");
    }
}
