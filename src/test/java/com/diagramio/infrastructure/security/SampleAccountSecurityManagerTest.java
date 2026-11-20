package com.diagramio.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.diagramio.domain.user.User;
import com.diagramio.domain.user.UserRepository;
import com.diagramio.infrastructure.user.UserJpaRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.env.Environment;

class SampleAccountSecurityManagerTest {

    private static final UUID SAMPLE_ID = SampleAccountSecurityManager.SAMPLE_USER_ID;
    private static final User SAMPLE_USER = new User(
            SAMPLE_ID,
            "uet",
            "uet@gmail.com",
            "$2a$10$jqmc./tQysLyXTNGg8M4CeIlAO5GffLskOQWPRLjPYfLidU3fVWEO",
            Instant.now()
    );

    private UserRepository userRepository;
    private UserJpaRepository userJpaRepository;
    private Environment environment;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        userJpaRepository = mock(UserJpaRepository.class);
        environment = mock(Environment.class);
    }

    @Test
    void devMode_keepAction_doesNotModifySampleAccount() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"dev"});
        when(userRepository.findById(SAMPLE_ID)).thenReturn(Optional.of(SAMPLE_USER));

        SampleAccountSecurityManager manager = new SampleAccountSecurityManager(
                userRepository,
                userJpaRepository,
                environment,
                "keep"
        );

        manager.handleSampleAccount();

        verify(userRepository, never()).save(any());
        verify(userJpaRepository, never()).deleteById(any());
    }

    @Test
    void prodMode_defaultsToDisable_updatesPasswordHashToDisabled() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"prod"});
        when(userRepository.findById(SAMPLE_ID)).thenReturn(Optional.of(SAMPLE_USER));

        SampleAccountSecurityManager manager = new SampleAccountSecurityManager(
                userRepository,
                userJpaRepository,
                environment,
                "keep" // Trong prod, keep tự động chuyển thành disable
        );

        manager.handleSampleAccount();

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());

        User savedUser = captor.getValue();
        assertThat(savedUser.id()).isEqualTo(SAMPLE_ID);
        assertThat(savedUser.passwordHash()).startsWith("DISABLED_");
        verify(userJpaRepository, never()).deleteById(any());
    }

    @Test
    void explicitDisableAction_updatesPasswordHashToDisabled() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"dev"});
        when(userRepository.findById(SAMPLE_ID)).thenReturn(Optional.of(SAMPLE_USER));

        SampleAccountSecurityManager manager = new SampleAccountSecurityManager(
                userRepository,
                userJpaRepository,
                environment,
                "disable"
        );

        manager.handleSampleAccount();

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().passwordHash()).startsWith("DISABLED_");
    }

    @Test
    void explicitDeleteAction_deletesSampleUser() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"prod"});
        when(userRepository.findById(SAMPLE_ID)).thenReturn(Optional.of(SAMPLE_USER));

        SampleAccountSecurityManager manager = new SampleAccountSecurityManager(
                userRepository,
                userJpaRepository,
                environment,
                "delete"
        );

        manager.handleSampleAccount();

        verify(userJpaRepository).deleteById(SAMPLE_ID);
        verify(userRepository, never()).save(any());
    }

    @Test
    void whenSampleUserNotFound_doesNothing() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"prod"});
        when(userRepository.findById(SAMPLE_ID)).thenReturn(Optional.empty());

        SampleAccountSecurityManager manager = new SampleAccountSecurityManager(
                userRepository,
                userJpaRepository,
                environment,
                "disable"
        );

        manager.handleSampleAccount();

        verify(userRepository, never()).save(any());
        verify(userJpaRepository, never()).deleteById(any());
    }
}
