package com.diagramio.config;

import com.diagramio.domain.diagram.DiagramRepository;
import com.diagramio.domain.share.DiagramShareRepository;
import com.diagramio.domain.share.ShareService;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Đăng ký các Bean cho Domain Services.
 * Tuân thủ Clean Architecture: Domain không dùng @Service hay annotation của Spring,
 * việc khởi tạo service được thực hiện tập trung tại tầng Config.
 */
@Configuration
public class ShareConfig {

    @Bean
    public ShareService shareService(DiagramShareRepository shareRepository, DiagramRepository diagramRepository,
                                     Clock clock, @Value("${sharing.default-link-ttl:7d}") Duration defaultLinkTtl) {
        return new ShareService(shareRepository, diagramRepository, clock, defaultLinkTtl);
    }
}
