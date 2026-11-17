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
}
