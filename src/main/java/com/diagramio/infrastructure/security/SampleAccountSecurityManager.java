package com.diagramio.infrastructure.security;

import com.diagramio.domain.user.User;
import com.diagramio.domain.user.UserRepository;
import com.diagramio.infrastructure.user.UserJpaRepository;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Quản lý và xử lý tài khoản mẫu (seed data uet@gmail.com) khi triển khai thực tế.
 *
 * <p>Quy tắc:
 * <ul>
 *   <li>Ở môi trường dev/demo: Tài khoản mẫu được giữ nguyên (action: keep) để phục vụ phát triển.</li>
 *   <li>Ở môi trường production (profile prod) hoặc khi cấu hình action: disable:
 *       Mật khẩu của tài khoản mẫu được đổi thành một chuỗi ngẫu nhiên không thể giải mã ("DISABLED_..."),
 *       ngăn chặn hoàn toàn việc đăng nhập bằng mật khẩu mặc định (uet123456) mà vẫn bảo toàn toàn vẹn dữ liệu.</li>
 *   <li>Khi cấu hình action: delete: Xóa tài khoản mẫu khỏi cơ sở dữ liệu.</li>
 * </ul>
 */
@Component
public class SampleAccountSecurityManager implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SampleAccountSecurityManager.class);
    public static final UUID SAMPLE_USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private final UserRepository userRepository;
    private final UserJpaRepository userJpaRepository;
    private final Environment environment;
    private final String sampleAccountAction;

    public SampleAccountSecurityManager(
            UserRepository userRepository,
            UserJpaRepository userJpaRepository,
            Environment environment,
            @Value("${sample-account.action:keep}") String sampleAccountAction) {
        this.userRepository = userRepository;
        this.userJpaRepository = userJpaRepository;
        this.environment = environment;
        this.sampleAccountAction = sampleAccountAction;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        handleSampleAccount();
    }

    public void handleSampleAccount() {
        boolean isProduction = Arrays.asList(environment.getActiveProfiles()).contains("prod");
        String action = isProduction && "keep".equalsIgnoreCase(sampleAccountAction) ? "disable" : sampleAccountAction;

        Optional<User> sampleUserOpt = userRepository.findById(SAMPLE_USER_ID);
        if (sampleUserOpt.isEmpty()) {
            return;
        }

        User user = sampleUserOpt.get();
        if ("disable".equalsIgnoreCase(action)) {
            if (!user.passwordHash().startsWith("DISABLED_")) {
                User disabledUser = user.withPasswordHash("DISABLED_" + UUID.randomUUID());
                userRepository.save(disabledUser);
                log.warn("CANH BAO BAO MAT: Tai khoan mau uet@gmail.com (ID: {}) da duoc vo hieu hoa mat khau de bao ve moi truong Production.", SAMPLE_USER_ID);
            }
        } else if ("delete".equalsIgnoreCase(action)) {
            userJpaRepository.deleteById(SAMPLE_USER_ID);
            log.warn("CANH BAO BAO MAT: Tai khoan mau uet@gmail.com (ID: {}) da bi xoa khoi he thong.", SAMPLE_USER_ID);
        } else {
            log.info("Tai khoan mau uet@gmail.com dang duoc giu nguyen cho muc dich phat trien/demo (sample-account.action={}).", action);
        }
    }
}
