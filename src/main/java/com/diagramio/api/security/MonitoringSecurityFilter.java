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
 * Filter bảo vệ và giới hạn quyền truy cập các endpoint giám sát Actuator (/actuator/**).
 *
 * <p>Quy tắc kiểm soát:
 * <ul>
 *   <li>Endpoint /actuator/health (và các probes /actuator/health/*) được mở công khai
 *       để Docker/Kubernetes kiểm tra sức khỏe dịch vụ.</li>
 *   <li>Tất cả các endpoint giám sát nhạy cảm khác (/actuator/prometheus, /actuator/metrics, ...)
 *       chỉ cho phép truy cập khi:
 *       <ol>
 *         <li>Request đến từ địa chỉ localhost/loopback (khi bật allowLocal = true), HOẶC</li>
 *         <li>Request có header <code>X-Monitoring-Token</code> khớp với <code>secretToken</code>, HOẶC</li>
 *         <li>Request có header <code>Authorization: Bearer &lt;secretToken&gt;</code>.</li>
 *       </ol>
 *   </li>
 *   <li>Các request không có quyền sẽ nhận phản hồi lỗi 403 Forbidden theo định dạng ErrorResponse thống nhất.</li>
 * </ul>
 */
public class MonitoringSecurityFilter extends OncePerRequestFilter {

    private static final Set<String> LOCAL_ADDRESSES = Set.of("127.0.0.1", "0:0:0:0:0:0:0:1", "::1", "localhost");

    private final String secretToken;
    private final boolean allowLocal;
    private final ObjectMapper objectMapper;

    public MonitoringSecurityFilter(String secretToken, boolean allowLocal, ObjectMapper objectMapper) {
        this.secretToken = secretToken;
        this.allowLocal = allowLocal;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String uri = request.getRequestURI();

        if (uri.startsWith("/actuator")) {
            // Cho phép công khai endpoint health kiểm tra trạng thái dịch vụ
            if (uri.equals("/actuator/health") || uri.startsWith("/actuator/health/")) {
                filterChain.doFilter(request, response);
                return;
            }

            // Kiểm tra phân quyền truy cập endpoint giám sát
            if (!isAuthorized(request)) {
                rejectAccess(request, response);
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private boolean isAuthorized(HttpServletRequest request) {
        // 1. Cho phép truy cập từ localhost/loopback nếu cấu hình cho phép
        if (allowLocal) {
            String remoteAddr = request.getRemoteAddr();
            if (remoteAddr != null && LOCAL_ADDRESSES.contains(remoteAddr)) {
                return true;
            }
        }

        if (secretToken == null || secretToken.isBlank()) {
            return false;
        }

        // 2. Kiểm tra header X-Monitoring-Token
        String tokenHeader = request.getHeader("X-Monitoring-Token");
        if (tokenHeader != null && tokenHeader.equals(secretToken)) {
            return true;
        }

        // 3. Kiểm tra header Authorization: Bearer <secretToken>
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String bearerToken = authHeader.substring(7).trim();
            if (bearerToken.equals(secretToken)) {
                return true;
            }
        }

        return false;
    }

    private void rejectAccess(HttpServletRequest request, HttpServletResponse response) throws IOException {
        HttpStatus status = HttpStatus.FORBIDDEN;
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        ErrorResponse errorResponse = new ErrorResponse(
                Instant.now(),
                status.value(),
                status.getReasonPhrase(),
                "Access denied: monitoring endpoints are restricted.",
                request.getRequestURI()
        );
        objectMapper.writeValue(response.getOutputStream(), errorResponse);
    }
}
