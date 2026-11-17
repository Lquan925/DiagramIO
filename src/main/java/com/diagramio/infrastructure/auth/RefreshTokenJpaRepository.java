package com.diagramio.infrastructure.auth;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenJpaRepository extends JpaRepository<RefreshTokenEntity, UUID> {

    Optional<RefreshTokenEntity> findByTokenHash(String tokenHash);

    /** SELECT ... FOR UPDATE: khóa dòng refresh token để các request đồng thời phải xếp hàng. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RefreshTokenEntity r where r.tokenHash = :tokenHash")
    Optional<RefreshTokenEntity> findByTokenHashWithLock(@Param("tokenHash") String tokenHash);

    /** Thu hồi tất cả các token còn hoạt động trong chuỗi token (familyId). */
    @Modifying
    @Query("update RefreshTokenEntity r set r.revoked = true, r.revokedAt = :now where r.familyId = :familyId and r.revoked = false")
    int revokeFamily(@Param("familyId") UUID familyId, @Param("now") Instant now);
}
