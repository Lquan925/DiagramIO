package com.diagramio.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InMemoryRateLimiterTest {

    private MutableClock clock;
    private InMemoryRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-11-18T12:00:00Z"));
        // 3 requests trong 60 giây
        rateLimiter = new InMemoryRateLimiter(3, Duration.ofSeconds(60), clock);
    }

    @Test
    void allowsRequestsUnderCapacity() {
        assertThat(rateLimiter.tryAcquire("client-1").allowed()).isTrue();
        assertThat(rateLimiter.tryAcquire("client-1").allowed()).isTrue();
        assertThat(rateLimiter.tryAcquire("client-1").allowed()).isTrue();
    }

    @Test
    void deniesRequestsExceedingCapacityAndCalculatesRetryAfter() {
        assertThat(rateLimiter.tryAcquire("client-1").allowed()).isTrue(); // t=0s
        clock.advance(Duration.ofSeconds(10));
        assertThat(rateLimiter.tryAcquire("client-1").allowed()).isTrue(); // t=10s
        clock.advance(Duration.ofSeconds(10));
        assertThat(rateLimiter.tryAcquire("client-1").allowed()).isTrue(); // t=20s

        // Request thứ 4 vượt giới hạn
        RateLimitResult result = rateLimiter.tryAcquire("client-1");
        assertThat(result.allowed()).isFalse();
        // Lượt gọi đầu tiên hết hạn ở t=60s, hiện tại là t=20s -> cần chờ 40 giây
        assertThat(result.retryAfterSeconds()).isEqualTo(40);
    }

    @Test
    void resetsAfterWindowExpires() {
        assertThat(rateLimiter.tryAcquire("client-1").allowed()).isTrue();
        assertThat(rateLimiter.tryAcquire("client-1").allowed()).isTrue();
        assertThat(rateLimiter.tryAcquire("client-1").allowed()).isTrue();
        assertThat(rateLimiter.tryAcquire("client-1").allowed()).isFalse();

        // Tiến thời gian qua 60 giây
        clock.advance(Duration.ofSeconds(61));

        // Lại được phép gửi tiếp
        assertThat(rateLimiter.tryAcquire("client-1").allowed()).isTrue();
    }

    @Test
    void tracksKeysIndependently() {
        assertThat(rateLimiter.tryAcquire("client-1").allowed()).isTrue();
        assertThat(rateLimiter.tryAcquire("client-1").allowed()).isTrue();
        assertThat(rateLimiter.tryAcquire("client-1").allowed()).isTrue();
        assertThat(rateLimiter.tryAcquire("client-1").allowed()).isFalse();

        // client-2 vẫn còn nguyên hạn mức
        assertThat(rateLimiter.tryAcquire("client-2").allowed()).isTrue();
    }

    @Test
    void resetClearsAllRecords() {
        assertThat(rateLimiter.tryAcquire("client-1").allowed()).isTrue();
        assertThat(rateLimiter.tryAcquire("client-1").allowed()).isTrue();
        assertThat(rateLimiter.tryAcquire("client-1").allowed()).isTrue();
        assertThat(rateLimiter.tryAcquire("client-1").allowed()).isFalse();

        rateLimiter.reset();

        assertThat(rateLimiter.tryAcquire("client-1").allowed()).isTrue();
    }

    private static class MutableClock extends Clock {
        private Instant current;

        public MutableClock(Instant initial) {
            this.current = initial;
        }

        public void advance(Duration duration) {
            this.current = this.current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
