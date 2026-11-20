package com.diagramio.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.diagramio.infrastructure.security.JwtProperties;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

class SecurityProfileValidatorTest {

    @Test
    void devEnvironment_allowsDefaultDevSecret() {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"dev"});

        JwtProperties jwtProps = new JwtProperties(
                SecurityProfileValidator.DEFAULT_DEV_SECRET,
                15,
                7
        );

        SecurityProfileValidator validator = new SecurityProfileValidator(env, jwtProps);

        assertThatCode(validator::validate).doesNotThrowAnyException();
    }

    @Test
    void devEnvironment_allowsEmptyActiveProfilesWithDefaultSecret() {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{});

        JwtProperties jwtProps = new JwtProperties(
                SecurityProfileValidator.DEFAULT_DEV_SECRET,
                15,
                7
        );

        SecurityProfileValidator validator = new SecurityProfileValidator(env, jwtProps);

        assertThatCode(validator::validate).doesNotThrowAnyException();
    }

    @Test
    void prodEnvironment_rejectsDefaultDevSecret() {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"prod"});

        JwtProperties jwtProps = new JwtProperties(
                SecurityProfileValidator.DEFAULT_DEV_SECRET,
                15,
                7
        );

        SecurityProfileValidator validator = new SecurityProfileValidator(env, jwtProps);

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Khong duoc phep su dung secret mac dinh cua dev");
    }

    @Test
    void prodEnvironment_rejectsEmptyOrNullSecret() {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"prod"});

        JwtProperties emptyProps = new JwtProperties("", 15, 7);
        SecurityProfileValidator validator1 = new SecurityProfileValidator(env, emptyProps);
        assertThatThrownBy(validator1::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Moi truong Production bat buoc phai cau hinh JWT secret rieng");

        JwtProperties nullProps = new JwtProperties(null, 15, 7);
        SecurityProfileValidator validator2 = new SecurityProfileValidator(env, nullProps);
        assertThatThrownBy(validator2::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Moi truong Production bat buoc phai cau hinh JWT secret rieng");
    }

    @Test
    void prodEnvironment_rejectsSecretShorterThan32Characters() {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"prod"});

        JwtProperties shortProps = new JwtProperties("short-secret-key-less-32-chars", 15, 7);
        SecurityProfileValidator validator = new SecurityProfileValidator(env, shortProps);

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("do dai toi thieu 32 ky tu");
    }

    @Test
    void prodEnvironment_acceptsValidDedicatedSecret() {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"prod"});

        JwtProperties validProps = new JwtProperties(
                "this-is-a-very-strong-and-secure-production-jwt-secret-key-32chars!",
                15,
                7
        );

        SecurityProfileValidator validator = new SecurityProfileValidator(env, validProps);

        assertThatCode(validator::validate).doesNotThrowAnyException();
    }
}
