package com.diagramio.infrastructure.auth;

import com.diagramio.domain.auth.RefreshTokenRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Tác vụ định kỳ dọn dẹp các refresh token đã hết hạn trong cơ sở dữ liệu.
 *
 * <p>Quy tắc dọn dẹp:
 * <ul>
 *   <li>Xóa theo từng lô nhỏ (mặc định 1.000 dòng/lô) để tránh giữ khóa bảng lâu và tránh tràn bộ nhớ.</li>
 *   <li>Áp dụng chính sách lưu giữ (retention policy, mặc định 7 ngày): Các token đã bị thu hồi
 *       (revoked) vẫn được giữ lại tối thiểu 7 ngày sau khi thu hồi nhằm phục vụ việc phát hiện
 *       tái sử dụng token trái phép (Token Reuse Detection).</li>
 * </ul>
 */
@Component
public class RefreshTokenCleanupTask {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenCleanupTask.class);

    private final RefreshTokenRepository refreshTokenRepository;
    private final Duration retentionPeriod;
    private final int batchSize;
    private final int maxBatchesPerRun;
    private final Clock clock;

    public RefreshTokenCleanupTask(
            RefreshTokenRepository refreshTokenRepository,
            @Value("${cleanup.refresh-tokens.retention-days:7}") long retentionDays,
            @Value("${cleanup.refresh-tokens.batch-size:1000}") int batchSize,
            @Value("${cleanup.refresh-tokens.max-batches:10}") int maxBatchesPerRun,
            Clock clock) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.retentionPeriod = Duration.ofDays(retentionDays);
        this.batchSize = batchSize;
        this.maxBatchesPerRun = maxBatchesPerRun;
        this.clock = clock;
    }

    /**
     * Chạy định kỳ (mặc định 3:00 sáng mỗi ngày).
     *
     * @return tổng số token đã dọn dẹp
     */
    @Scheduled(cron = "${cleanup.refresh-tokens.cron:0 0 3 * * *}")
    public int cleanUpExpiredTokens() {
        return cleanUpExpiredTokens(clock.instant());
    }

    /**
     * Dọn dẹp với mốc thời gian chỉ định (thuận tiện cho việc gọi trực tiếp và kiểm thử).
     *
     * @param now mốc thời gian hiện tại
     * @return tổng số token đã dọn dẹp
     */
    public int cleanUpExpiredTokens(Instant now) {
        Instant expiredBefore = now;
        Instant revokedBefore = now.minus(retentionPeriod);

        int totalDeleted = 0;
        int batchesRun = 0;

        while (batchesRun < maxBatchesPerRun) {
            int deleted = refreshTokenRepository.deleteExpiredTokensBatch(expiredBefore, revokedBefore, batchSize);
            totalDeleted += deleted;
            batchesRun++;

            if (deleted < batchSize) {
                break; // Hết bản ghi thỏa mãn điều kiện
            }
        }

        if (totalDeleted > 0) {
            log.info("Dọn dẹp thành công {} refresh token hết hạn trong {} lô", totalDeleted, batchesRun);
        }
        return totalDeleted;
    }
}
