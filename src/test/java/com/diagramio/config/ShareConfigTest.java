package com.diagramio.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.diagramio.domain.access.Caller;
import com.diagramio.domain.access.ShareRole;
import com.diagramio.domain.diagram.Diagram;
import com.diagramio.domain.diagram.DiagramData;
import com.diagramio.domain.diagram.DiagramRepository;
import com.diagramio.domain.diagram.DiagramType;
import com.diagramio.domain.share.DiagramShare;
import com.diagramio.domain.share.DiagramShareRepository;
import com.diagramio.domain.share.ShareService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

class ShareConfigTest {

    private static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");
    private static final UUID OWNER_ID = UUID.randomUUID();
    private static final UUID DIAGRAM_ID = UUID.randomUUID();

    @Test
    void missingPropertyDefaultsToSevenDays() {
        try (ConfigurableApplicationContext context = application().run()) {
            assertExpiry(context, Duration.ofDays(7));
        }
    }

    @ParameterizedTest
    @CsvSource({"7d,604800", "2h,7200", "PT30M,1800"})
    void configuredDurationIsConvertedBySpringBoot(String value, long seconds) {
        try (ConfigurableApplicationContext context = application().run("--sharing.default-link-ttl=" + value)) {
            assertExpiry(context, Duration.ofSeconds(seconds));
        }
    }

    private SpringApplicationBuilder application() {
        return new SpringApplicationBuilder(TestBeans.class)
                .web(WebApplicationType.NONE)
                .registerShutdownHook(false)
                .properties("spring.config.name=share-config-test", "spring.main.banner-mode=off");
    }

    private void assertExpiry(ConfigurableApplicationContext context, Duration expectedTtl) {
        ShareService service = context.getBean(ShareService.class);
        DiagramShare created = service.createShare(DIAGRAM_ID, ShareRole.VIEW, null, Caller.user(OWNER_ID));
        assertThat(created.getCreatedAt()).isEqualTo(NOW);
        assertThat(created.getExpiresAt()).isEqualTo(NOW.plus(expectedTtl));
    }

    @TestConfiguration(proxyBeanMethods = false)
    @Import(ShareConfig.class)
    static class TestBeans {

        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean
        DiagramRepository diagramRepository() {
            DiagramRepository repository = mock(DiagramRepository.class);
            when(repository.findById(DIAGRAM_ID)).thenReturn(Optional.of(new Diagram(DIAGRAM_ID, "Share expiry",
                    DiagramType.UML, DiagramData.EMPTY, 1, false, OWNER_ID, OWNER_ID, NOW, NOW)));
            return repository;
        }

        @Bean
        DiagramShareRepository shareRepository() {
            DiagramShareRepository repository = mock(DiagramShareRepository.class);
            when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
            return repository;
        }
    }
}
