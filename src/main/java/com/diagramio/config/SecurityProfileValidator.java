package com.diagramio.config;

import com.diagramio.infrastructure.security.JwtProperties;
import jakarta.annotation.PostConstruct;
import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Kiểm tra các ràng buộc bảo mật khi triển khai ứng dụng.
 *
 * <p>Quy tắc kiểm tra:
 * <ul>
 *   <li>Khi chạy ở môi trường Production (profile <code>prod</code>), BẮT BUỘC phải cung cấp
 *       <code>JWT_SECRET</code> riêng an toàn (tối thiểu 32 ký tự).</li>
 *   <li>Tuyệt đối không được phép sử dụng secret mặc định của môi trường dev trên production.
 *       Ứng dụng sẽ fail-fast (từ chối khởi động) ngay lập tức nếu vi phạm.</li>
 * </ul>
 */
@Component
public class SecurityProfileValidator {

    private static final Logger log = LoggerFactory.getLogger(SecurityProfileValidator.class);
    public static final String DEFAULT_DEV_SECRET = "dev-only-secret-please-change-it-32chars";

    private final Environment environment;
    private final JwtProperties jwtProperties;

    public SecurityProfileValidator(Environment environment, JwtProperties jwtProperties) {
        this.environment = environment;
        this.jwtProperties = jwtProperties;
    }

    @PostConstruct
    public void validate() {
        boolean isProduction = Arrays.asList(environment.getActiveProfiles()).contains("prod");
        if (isProduction) {
            String secret = jwtProperties.secret();
            if (secret == null || secret.isBlank() || DEFAULT_DEV_SECRET.equals(secret)) {
                throw new IllegalStateException(
                        "KHOI DONG THAT BAI: Moi truong Production bat buoc phai cau hinh JWT secret rieng "
                                + "(bien moi truong JWT_SECRET). Khong duoc phep su dung secret mac dinh cua dev!");
            }
            if (secret.length() < 32) {
                throw new IllegalStateException(
                        "KHOI DONG THAT BAI: JWT secret trong moi truong Production phai co do dai toi thieu 32 ky tu!");
            }
            log.info("Xac thuc cau hinh bao mat Production thanh cong: JWT secret rieng da duoc thiet lap an toan.");
        }
    }
}
