package com.diagramio.config;

import com.diagramio.api.dto.ErrorResponse;
import com.diagramio.api.security.InMemoryRateLimiter;
import com.diagramio.api.security.MonitoringSecurityFilter;
import com.diagramio.api.security.RateLimitFilter;
import com.diagramio.api.security.RateLimiter;
import com.diagramio.domain.share.ShareService;
import com.diagramio.infrastructure.security.JwtAuthenticationFilter;
import com.diagramio.infrastructure.security.JwtTokenProvider;
import com.diagramio.infrastructure.security.ShareTokenAuthenticationFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Bảo mật: stateless, endpoint công khai được mở, còn lại phải xác thực.
 * /api/v1/shares/** công khai để xem sơ đồ qua link chia sẻ; GET /api/v1/diagrams/* công khai cho sơ đồ public.
 * Người gọi được xác định bởi filter JWT rồi filter X-Share-Token (link chia sẻ); hai filter không tự
 * từ chối, request chưa xác thực vào endpoint cần đăng nhập sẽ nhận 401 dạng JSON.
 * Endpoint xác thực được bảo vệ bởi RateLimitFilter (trả 429 khi vượt ngưỡng).
 */
@Configuration
public class SecurityConfig {

    private static final String[] PUBLIC_ENDPOINTS = {
            "/api/v1/health",
            "/api/v1/auth/register",
            "/api/v1/auth/login",
            "/api/v1/auth/refresh-token",
            "/api/v1/shares/**",
            "/v3/api-docs/**",
            "/v3/api-docs.yaml",
            "/swagger-ui.html",
            "/swagger-ui/**",
            "/actuator/**"
    };

    @Bean
    public RateLimiter rateLimiter(
            @Value("${rate-limit.auth.max-requests:10}") int maxRequests,
            @Value("${rate-limit.auth.window-seconds:60}") int windowSeconds) {
        return new InMemoryRateLimiter(maxRequests, Duration.ofSeconds(windowSeconds));
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${cors.allowed-origins:http://localhost:3000,http://localhost:5173}") List<String> allowedOrigins,
            @Value("${cors.allowed-methods:GET,POST,PUT,PATCH,DELETE,OPTIONS}") List<String> allowedMethods,
            @Value("${cors.allowed-headers:*}") List<String> allowedHeaders,
            @Value("${cors.exposed-headers:Authorization,Retry-After,X-Share-Token}") List<String> exposedHeaders,
            @Value("${cors.allow-credentials:true}") boolean allowCredentials,
            @Value("${cors.max-age-seconds:3600}") long maxAgeSeconds) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(allowedMethods);
        configuration.setAllowedHeaders(allowedHeaders);
        configuration.setExposedHeaders(exposedHeaders);
        configuration.setAllowCredentials(allowCredentials);
        configuration.setMaxAge(maxAgeSeconds);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtTokenProvider jwtTokenProvider,
                                                   ShareService shareService,
                                                   RateLimiter rateLimiter,
                                                   ObjectMapper objectMapper,
                                                   CorsConfigurationSource corsConfigurationSource,
                                                   @Value("${monitoring.secret-token:monitoring-secret-token-32chars-ok}") String monitoringSecretToken,
                                                   @Value("${monitoring.allow-local:true}") boolean monitoringAllowLocal)
            throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(e -> e.authenticationEntryPoint(jsonUnauthorizedEntryPoint(objectMapper)))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(PUBLIC_ENDPOINTS).permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/diagrams/*").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(new MonitoringSecurityFilter(monitoringSecretToken, monitoringAllowLocal, objectMapper),
                        UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new RateLimitFilter(rateLimiter, objectMapper),
                        UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new JwtAuthenticationFilter(jwtTokenProvider),
                        UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(new ShareTokenAuthenticationFilter(shareService), JwtAuthenticationFilter.class);
        return http.build();
    }

    /** Trả 401 cùng khung JSON với GlobalExceptionHandler. */
    private static AuthenticationEntryPoint jsonUnauthorizedEntryPoint(ObjectMapper objectMapper) {
        return (request, response, exception) -> {
            HttpStatus status = HttpStatus.UNAUTHORIZED;
            response.setStatus(status.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            objectMapper.writeValue(response.getOutputStream(), new ErrorResponse(Instant.now(), status.value(),
                    status.getReasonPhrase(), "Authentication required", request.getRequestURI()));
        };
    }
}
