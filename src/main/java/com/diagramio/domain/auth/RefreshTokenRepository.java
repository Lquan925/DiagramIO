package com.diagramio.domain.auth;

import java.util.Optional;

/** Cổng truy cập dữ liệu refresh token; tầng infrastructure cài đặt bằng JPA. */
public interface RefreshTokenRepository {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /** Đọc refresh token kèm khóa dòng (SELECT ... FOR UPDATE) để đồng bộ các request làm mới đồng thời. */
    Optional<RefreshToken> findByTokenHashWithLock(String tokenHash);

    RefreshToken save(RefreshToken refreshToken);

    /** Thu hồi toàn bộ các token còn hoạt động trong cùng một chuỗi token (token family). */
    void revokeFamily(java.util.UUID familyId, java.time.Instant now);

    /**
     * Xóa các token hết hạn và đủ điều kiện theo chính sách lưu giữ theo lô nhỏ (batch).
     *
     * @param expiredBefore thời điểm hết hạn tối đa (thường là now)
     * @param revokedBefore thời điểm thu hồi tối đa theo chính sách lưu giữ (giữ lại token bị thu hồi gần đây để phát hiện tái sử dụng)
     * @param batchSize số lượng bản ghi tối đa xóa trong một lô
     * @return số lượng bản ghi thực tế đã bị xóa trong lô này
     */
    int deleteExpiredTokensBatch(java.time.Instant expiredBefore, java.time.Instant revokedBefore, int batchSize);
}
