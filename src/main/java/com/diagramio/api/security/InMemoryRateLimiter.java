package com.diagramio.api.security;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thuật toán Sliding Window Log in-memory kiểm soát tần suất gọi API theo từng khóa (key).
 * Đảm bảo thread-safe và hỗ trợ đồng hồ (Clock) tùy biến cho việc kiểm thử.
 */
public class InMemoryRateLimiter implements RateLimiter {

    private final int maxRequests;
    private final Duration windowDuration;
    private final Clock clock;
    private final ConcurrentHashMap<String, Deque<Long>> requestLogs = new ConcurrentHashMap<>();

    public InMemoryRateLimiter(int maxRequests, Duration windowDuration) {
        this(maxRequests, windowDuration, Clock.systemUTC());
    }

    public InMemoryRateLimiter(int maxRequests, Duration windowDuration, Clock clock) {
        this.maxRequests = maxRequests;
        this.windowDuration = windowDuration;
        this.clock = clock;
    }

    @Override
    public RateLimitResult tryAcquire(String key) {
        long now = clock.millis();
        long windowStart = now - windowDuration.toMillis();

        Deque<Long> timestamps = requestLogs.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (timestamps) {
            // Loại bỏ các mốc thời gian đã trôi ra ngoài cửa sổ trượt
            while (!timestamps.isEmpty() && timestamps.peekFirst() <= windowStart) {
                timestamps.pollFirst();
            }

            if (timestamps.size() < maxRequests) {
                timestamps.addLast(now);
                return RateLimitResult.allow();
            }

            // Tính toán thời gian tối thiểu cần chờ cho đến khi lượt gọi cũ nhất hết hiệu lực
            long oldest = timestamps.peekFirst();
            long retryAfterMs = (oldest + windowDuration.toMillis()) - now;
            long retryAfterSec = Math.max(1, (retryAfterMs + 999) / 1000);
            return RateLimitResult.deny(retryAfterSec);
        }
    }

    /** Xóa sạch toàn bộ nhật ký (dùng cho việc reset giữa các bài test). */
    public void reset() {
        requestLogs.clear();
    }
}
