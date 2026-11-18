package com.diagramio.api.security;

/** Cổng kiểm tra giới hạn tần suất gọi API, tách biệt thuật toán khỏi filter. */
public interface RateLimiter {

    /**
     * Thử tiêu thụ một lượt gọi cho khóa định danh (ví dụ: clientIp:endpoint).
     *
     * @param key khóa định danh của client hoặc hành động
     * @return kết quả cho phép hay từ chối kèm thời gian chờ (Retry-After)
     */
    RateLimitResult tryAcquire(String key);
}
