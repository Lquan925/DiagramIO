package com.diagramio.domain.auth;

import java.time.Instant;
import java.util.UUID;

/**
 * Refresh token đã lưu. Chỉ giữ bản băm SHA-256 (tokenHash), không giữ token gốc.
 * familyId đại diện cho chuỗi token (token family) từ một lần đăng nhập.
 * revokedAt lưu thời điểm token bị xoay vòng hoặc thu hồi, phục vụ việc phát hiện tái sử dụng.
 */
public record RefreshToken(
        UUID id,
        UUID userId,
        String tokenHash,
        Instant expiresAt,
        boolean revoked,
        UUID familyId,
        Instant revokedAt) {

    /** Constructor tiện ích cho các trường hợp khởi tạo mặc định/tương thích cũ. */
    public RefreshToken(UUID id, UUID userId, String tokenHash, Instant expiresAt, boolean revoked) {
        this(id, userId, tokenHash, expiresAt, revoked, id, revoked ? expiresAt : null);
    }

    /** Còn dùng được: chưa bị thu hồi và chưa hết hạn. */
    public boolean isUsable(Instant now) {
        return !revoked && now.isBefore(expiresAt);
    }

    /** Thu hồi token tại thời điểm chỉ định. */
    public RefreshToken revoke(Instant now) {
        return new RefreshToken(id, userId, tokenHash, expiresAt, true, familyId, now);
    }

    public RefreshToken revoke() {
        return revoke(Instant.now());
    }
}
