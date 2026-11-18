package com.diagramio.api.security;

import com.diagramio.api.dto.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Filter kiểm soát tần suất gửi request tới các endpoint xác thực nhạy cảm.
 * Chặn sớm các cuộc tấn công brute-force / spam tài nguyên (như băm BCrypt) trước khi chạm tới Controller hay DB.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Set<String> PROTECTED_ENDPOINTS = Set.of(
            "/api/v1/auth/login",
            "/api/v1/auth/register",
            "/api/v1/auth/refresh-token"
    );

    private final RateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(RateLimiter rateLimiter, ObjectMapper objectMapper) {
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (isProtected(request)) {
            String clientIp = resolveClientIp(request);
            String key = clientIp + ":" + request.getRequestURI();
            RateLimitResult result = rateLimiter.tryAcquire(key);

            if (!result.allowed()) {
                handleRateLimitExceeded(request, response, result.retryAfterSeconds());
                return;
            }
        }
        filterChain.doFilter(request, response);
    }

    private boolean isProtected(HttpServletRequest request) {
        return "POST".equalsIgnoreCase(request.getMethod())
                && PROTECTED_ENDPOINTS.contains(request.getRequestURI());
    }

    private String resolveClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int commaIndex = xff.indexOf(',');
            return (commaIndex != -1 ? xff.substring(0, commaIndex) : xff).trim();
        }
        return request.getRemoteAddr();
    }

    private void handleRateLimitExceeded(HttpServletRequest request,
                                         HttpServletResponse response,
                                         long retryAfterSeconds) throws IOException {
        HttpStatus status = HttpStatus.TOO_MANY_REQUESTS;
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));

        ErrorResponse errorResponse = new ErrorResponse(
                Instant.now(),
                status.value(),
                status.getReasonPhrase(),
                "Too many requests. Please try again later.",
                request.getRequestURI()
        );
        objectMapper.writeValue(response.getOutputStream(), errorResponse);
    }
}
