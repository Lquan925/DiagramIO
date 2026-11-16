package com.diagramio.infrastructure.cache;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "cache")
public record ReadCacheProperties(Mode mode, long diagramMaxWeightBytes, Duration diagramTtl,
                                  long shareMaxEntries, Duration shareTtl) {

    public enum Mode { DISABLED, LOCAL }

    public ReadCacheProperties {
        Objects.requireNonNull(mode, "cache.mode is required");
        if (diagramMaxWeightBytes <= 0 || shareMaxEntries <= 0) {
            throw new IllegalArgumentException("Cache capacity must be positive");
        }
        requirePositiveTtl(diagramTtl);
        requirePositiveTtl(shareTtl);
    }

    private static void requirePositiveTtl(Duration ttl) {
        Objects.requireNonNull(ttl, "Cache TTL is required");
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("Cache TTL must be positive");
        }
        ttl.toNanos();
    }
}
