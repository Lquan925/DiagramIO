package com.diagramio.infrastructure.cache;

import com.diagramio.domain.diagram.Diagram;
import com.diagramio.domain.share.DiagramShare;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.Ticker;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public final class ReadCache {

    private final boolean enabled;
    private final Clock clock;
    private final Cache<UUID, Diagram> diagrams;
    private final Cache<String, DiagramShare> shares;
    private final Object monitor = new Object();
    private long generation;

    public ReadCache(ReadCacheProperties properties, Clock clock, Ticker ticker) {
        this.enabled = properties.mode() == ReadCacheProperties.Mode.LOCAL;
        this.clock = clock;
        this.diagrams = Caffeine.newBuilder()
                .maximumWeight(properties.diagramMaxWeightBytes())
                .weigher((UUID id, Diagram diagram) -> 512 + 2 * diagram.getData().json().length()
                        + 2 * diagram.getTitle().length())
                .expireAfterWrite(properties.diagramTtl())
                .ticker(ticker)
                .recordStats()
                .build();
        this.shares = Caffeine.newBuilder()
                .maximumSize(properties.shareMaxEntries())
                .expireAfter(new Expiry<String, DiagramShare>() {
                    @Override
                    public long expireAfterCreate(String key, DiagramShare value, long currentTime) {
                        if (value.getExpiresAt() == null) {
                            return properties.shareTtl().toNanos();
                        }
                        Duration remaining = Duration.between(clock.instant(), value.getExpiresAt());
                        return remaining.isNegative() ? 0
                                : remaining.compareTo(properties.shareTtl()) < 0
                                ? remaining.toNanos() : properties.shareTtl().toNanos();
                    }

                    @Override
                    public long expireAfterUpdate(String key, DiagramShare value, long currentTime, long currentDuration) {
                        return expireAfterCreate(key, value, currentTime);
                    }

                    @Override
                    public long expireAfterRead(String key, DiagramShare value, long currentTime, long currentDuration) {
                        return currentDuration;
                    }
                })
                .ticker(ticker)
                .recordStats()
                .build();
    }

    public Optional<Diagram> findDiagram(UUID id, Supplier<Optional<Diagram>> loader) {
        return load(diagrams, id, () -> loader.get().map(ReadCache::copy)).map(ReadCache::copy);
    }

    public Optional<DiagramShare> findShare(String token, Supplier<Optional<DiagramShare>> loader) {
        String key = tokenKey(token);
        Optional<DiagramShare> share = load(shares, key,
                () -> loader.get().filter(value -> !value.isExpired(clock.instant())));
        if (share.isPresent() && share.get().isExpired(clock.instant())) {
            synchronized (monitor) {
                shares.invalidate(key);
            }
            return Optional.empty();
        }
        return share;
    }

    private <K, V> Optional<V> load(Cache<K, V> cache, K key, Supplier<Optional<V>> loader) {
        // Locked snapshot/restore reads must observe the database, not an earlier cached version.
        if (!enabled || TransactionSynchronizationManager.isActualTransactionActive()) {
            return loader.get();
        }
        while (true) {
            long startedAt;
            synchronized (monitor) {
                V cached = cache.getIfPresent(key);
                if (cached != null) {
                    return Optional.of(cached);
                }
                startedAt = generation;
            }
            Optional<V> loaded = loader.get();
            synchronized (monitor) {
                // A write completed while the SELECT was in flight; never publish that old result.
                if (startedAt != generation) {
                    continue;
                }
                loaded.ifPresent(value -> cache.put(key, value));
                return loaded;
            }
        }
    }

    public void diagramChanged(UUID id) {
        afterCommit(() -> {
            synchronized (monitor) {
                generation++;
                diagrams.invalidate(id);
            }
        });
    }

    public void diagramDeleted(UUID id) {
        afterCommit(() -> {
            synchronized (monitor) {
                generation++;
                diagrams.invalidate(id);
                shares.invalidateAll();
            }
        });
    }

    public void sharesChanged() {
        afterCommit(() -> {
            synchronized (monitor) {
                generation++;
                shares.invalidateAll();
            }
        });
    }

    private void afterCommit(Runnable invalidation) {
        if (!enabled) {
            return;
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    invalidation.run();
                }
            });
        } else {
            invalidation.run();
        }
    }

    public CacheStats diagramStats() {
        return diagrams.stats();
    }

    public CacheStats shareStats() {
        return shares.stats();
    }

    private static Diagram copy(Diagram diagram) {
        return new Diagram(diagram.getId(), diagram.getTitle(), diagram.getDiagramType(), diagram.getData(),
                diagram.getVersion(), diagram.isPublic(), diagram.getOwnerId(), diagram.getUpdatedBy(),
                diagram.getCreatedAt(), diagram.getUpdatedAt());
    }

    private static String tokenKey(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
