package com.diagramio.infrastructure.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.diagramio.domain.auth.RefreshTokenRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RefreshTokenCleanupTaskTest {

    private static final Instant NOW = Instant.parse("2026-10-22T10:00:00Z");
    private static final long RETENTION_DAYS = 7;
    private static final int BATCH_SIZE = 100;
    private static final int MAX_BATCHES = 5;

    private RefreshTokenRepository refreshTokenRepository;
    private RefreshTokenCleanupTask cleanupTask;
    private Clock clock;

    @BeforeEach
    void setUp() {
        refreshTokenRepository = mock(RefreshTokenRepository.class);
        clock = Clock.fixed(NOW, ZoneOffset.UTC);
        cleanupTask = new RefreshTokenCleanupTask(
                refreshTokenRepository,
                RETENTION_DAYS,
                BATCH_SIZE,
                MAX_BATCHES,
                clock
        );
    }

    @Test
    void cleanUpExpiredTokens_stopsAfterSingleBatchWhenDeletedIsLessThanBatchSize() {
        when(refreshTokenRepository.deleteExpiredTokensBatch(any(), any(), eq(BATCH_SIZE)))
                .thenReturn(42);

        int totalDeleted = cleanupTask.cleanUpExpiredTokens(NOW);

        assertThat(totalDeleted).isEqualTo(42);

        ArgumentCaptor<Instant> expiredBeforeCaptor = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> revokedBeforeCaptor = ArgumentCaptor.forClass(Instant.class);

        verify(refreshTokenRepository, times(1)).deleteExpiredTokensBatch(
                expiredBeforeCaptor.capture(),
                revokedBeforeCaptor.capture(),
                eq(BATCH_SIZE)
        );

        assertThat(expiredBeforeCaptor.getValue()).isEqualTo(NOW);
        assertThat(revokedBeforeCaptor.getValue()).isEqualTo(NOW.minus(Duration.ofDays(RETENTION_DAYS)));
    }

    @Test
    void cleanUpExpiredTokens_loopsAcrossMultipleBatchesUntilNotFull() {
        when(refreshTokenRepository.deleteExpiredTokensBatch(any(), any(), eq(BATCH_SIZE)))
                .thenReturn(100) // Batch 1
                .thenReturn(100) // Batch 2
                .thenReturn(35); // Batch 3 (cuối)

        int totalDeleted = cleanupTask.cleanUpExpiredTokens(NOW);

        assertThat(totalDeleted).isEqualTo(235);
        verify(refreshTokenRepository, times(3)).deleteExpiredTokensBatch(
                eq(NOW),
                eq(NOW.minus(Duration.ofDays(RETENTION_DAYS))),
                eq(BATCH_SIZE)
        );
    }

    @Test
    void cleanUpExpiredTokens_stopsWhenMaxBatchesReachedEvenIfBatchesAreFull() {
        when(refreshTokenRepository.deleteExpiredTokensBatch(any(), any(), eq(BATCH_SIZE)))
                .thenReturn(100);

        int totalDeleted = cleanupTask.cleanUpExpiredTokens(NOW);

        assertThat(totalDeleted).isEqualTo(500); // 5 * 100
        verify(refreshTokenRepository, times(MAX_BATCHES)).deleteExpiredTokensBatch(
                eq(NOW),
                eq(NOW.minus(Duration.ofDays(RETENTION_DAYS))),
                eq(BATCH_SIZE)
        );
    }

    @Test
    void cleanUpExpiredTokens_scheduledNoArgUsesClockInstant() {
        when(refreshTokenRepository.deleteExpiredTokensBatch(any(), any(), eq(BATCH_SIZE)))
                .thenReturn(10);

        int totalDeleted = cleanupTask.cleanUpExpiredTokens();

        assertThat(totalDeleted).isEqualTo(10);
        verify(refreshTokenRepository).deleteExpiredTokensBatch(
                eq(NOW),
                eq(NOW.minus(Duration.ofDays(RETENTION_DAYS))),
                eq(BATCH_SIZE)
        );
    }

    @Test
    void cleanUpExpiredTokens_zeroDeletedWhenNoExpiredTokens() {
        when(refreshTokenRepository.deleteExpiredTokensBatch(any(), any(), eq(BATCH_SIZE)))
                .thenReturn(0);

        int totalDeleted = cleanupTask.cleanUpExpiredTokens(NOW);

        assertThat(totalDeleted).isZero();
        verify(refreshTokenRepository, times(1)).deleteExpiredTokensBatch(any(), any(), eq(BATCH_SIZE));
    }
}
