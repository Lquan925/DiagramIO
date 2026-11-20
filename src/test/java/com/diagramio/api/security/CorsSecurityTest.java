package com.diagramio.api.security;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.diagramio.api.HealthController;
import com.diagramio.config.SecurityConfig;
import com.diagramio.domain.DatabaseHealth;
import com.diagramio.domain.share.ShareService;
import com.diagramio.infrastructure.security.JwtTokenProvider;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(HealthController.class)
@Import(SecurityConfig.class)
class CorsSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private ShareService shareService;

    @MockitoBean
    private DatabaseHealth databaseHealth;

    @BeforeEach
    void setUp() {
        when(databaseHealth.isUp()).thenReturn(true);
    }

    @Test
    void preflightOptionsWithAllowedOrigin_returnsOkAndCorsHeaders() throws Exception {
        mockMvc.perform(options("/api/v1/health")
                        .header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "Authorization,Content-Type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"))
                .andExpect(header().string("Access-Control-Allow-Methods", Matchers.containsString("GET")))
                .andExpect(header().string("Access-Control-Expose-Headers", Matchers.containsString("Retry-After")));
    }

    @Test
    void preflightOptionsWithDisallowedOrigin_isForbidden() throws Exception {
        mockMvc.perform(options("/api/v1/health")
                        .header("Origin", "http://malicious-attacker.com")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());
    }

    @Test
    void getRequestWithAllowedOrigin_containsCorsHeader() throws Exception {
        mockMvc.perform(get("/api/v1/health")
                        .header("Origin", "http://localhost:5173"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
    }

    @Test
    void getRequestWithDisallowedOrigin_isForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/health")
                        .header("Origin", "http://malicious-attacker.com"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
}
