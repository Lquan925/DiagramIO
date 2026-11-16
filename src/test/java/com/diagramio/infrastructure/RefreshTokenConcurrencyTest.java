package com.diagramio.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.diagramio.domain.auth.AuthService;
import com.diagramio.domain.auth.AuthTokens;
import com.diagramio.domain.auth.RefreshToken;
import com.diagramio.domain.auth.RefreshTokenRepository;
import com.diagramio.domain.exception.UnauthorizedException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Nhiều luồng gọi refresh-token cùng lúc với cùng một token trên PostgreSQL thật:
 * khóa dòng (SELECT ... FOR UPDATE) xếp hàng các giao dịch, bảo đảm duy nhất một luồng
 * đổi token thành công, các luồng còn lại nhận lỗi UnauthorizedException.
 * Bỏ qua khi máy không có Docker.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class RefreshTokenConcurrencyTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired
    private AuthService authService;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Test
    void concurrentRefreshOnSameTokenAllowsExactlyOneSuccess() throws Exception {
        // Đăng nhập tài khoản mẫu được seed sẵn từ V2 (uet@gmail.com / uet123456)
        AuthTokens initialTokens = authService.login("uet@gmail.com", "uet123456");
        String sharedRefreshToken = initialTokens.refreshToken();

        int threadCount = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startSignal = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger unauthorizedCount = new AtomicInteger();

        try {
            List<Future<AuthTokens>> futures = new ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                Callable<AuthTokens> task = () -> {
                    startSignal.await();
                    return authService.refresh(sharedRefreshToken);
                };
                futures.add(pool.submit(task));
            }

            // Đồng loạt kích hoạt tất cả các luồng cùng gọi refresh tại một thời điểm
            startSignal.countDown();

            AuthTokens successfulTokens = null;
            for (Future<AuthTokens> future : futures) {
                try {
                    AuthTokens result = future.get(30, TimeUnit.SECONDS);
                    successfulTokens = result;
                    successCount.incrementAndGet();
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof UnauthorizedException) {
                        unauthorizedCount.incrementAndGet();
                    } else {
                        throw e;
                    }
                }
            }

            // Khẳng định: Duy nhất 1 luồng thành công, 9 luồng còn lại bị từ chối với 401 (Unauthorized)
            assertThat(successCount.get()).isEqualTo(1);
            assertThat(unauthorizedCount.get()).isEqualTo(threadCount - 1);
            assertThat(successfulTokens).isNotNull();
            assertThat(successfulTokens.refreshToken()).isNotEqualTo(sharedRefreshToken);

            // Token ban đầu chắc chắn đã bị thu hồi (revoked = true)
            RefreshToken originalInDb = refreshTokenRepository.findByTokenHash(AuthService.hash(sharedRefreshToken))
                    .orElseThrow();
            assertThat(originalInDb.revoked()).isTrue();

            // Token mới sinh ra từ luồng thành công vẫn còn hoạt động bình thường
            RefreshToken newInDb = refreshTokenRepository.findByTokenHash(AuthService.hash(successfulTokens.refreshToken()))
                    .orElseThrow();
            assertThat(newInDb.revoked()).isFalse();
        } finally {
            pool.shutdownNow();
        }
    }
}
