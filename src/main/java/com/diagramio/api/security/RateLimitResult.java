package com.diagramio.api.security;

/** Kết quả kiểm tra giới hạn tần suất (rate limiting). */
public record RateLimitResult(boolean allowed, long retryAfterSeconds) {

    public static RateLimitResult allow() {
        return new RateLimitResult(true, 0);
    }

    public static RateLimitResult deny(long retryAfterSeconds) {
        return new RateLimitResult(false, retryAfterSeconds);
    }
}
