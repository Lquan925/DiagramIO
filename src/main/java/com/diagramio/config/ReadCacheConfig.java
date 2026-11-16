package com.diagramio.config;

import com.diagramio.infrastructure.cache.ReadCache;
import com.diagramio.infrastructure.cache.ReadCacheProperties;
import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ReadCacheProperties.class)
public class ReadCacheConfig {

    @Bean
    public ReadCache readCache(ReadCacheProperties properties, Clock clock) {
        return new ReadCache(properties, clock, Ticker.systemTicker());
    }
}
