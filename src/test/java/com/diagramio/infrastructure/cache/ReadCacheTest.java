package com.diagramio.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.diagramio.domain.access.ShareRole;
import com.diagramio.domain.diagram.Diagram;
import com.diagramio.domain.diagram.DiagramData;
import com.diagramio.domain.diagram.DiagramType;
import com.diagramio.domain.share.DiagramShare;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class ReadCacheTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Duration TTL = Duration.ofSeconds(10);
    private static final UUID DIAGRAM_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OWNER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String TOKEN = "abc";

    private final FakeTicker ticker = new FakeTicker();
    private final ReadCache cache = newCache(ReadCacheProperties.Mode.LOCAL, 16_384, 10);

    @Test
    void repeatedDiagramHitsReturnIndependentSnapshotsOfEveryField() {
        Diagram databaseValue = diagram(DIAGRAM_ID, "Original");
        Diagram expected = diagram(DIAGRAM_ID, "Original");
        Loader<Diagram> loader = new Loader<>(databaseValue);

        Diagram first = cache.findDiagram(DIAGRAM_ID, loader).orElseThrow();
        assertThat(first).isNotSameAs(databaseValue).usingRecursiveComparison().isEqualTo(expected);
        mutateEveryField(databaseValue);
        mutateEveryField(first);

        Diagram second = cache.findDiagram(DIAGRAM_ID, loader).orElseThrow();
        assertThat(second).isNotSameAs(first).usingRecursiveComparison().isEqualTo(expected);
        mutateEveryField(second);
        assertThat(cache.findDiagram(DIAGRAM_ID, loader).orElseThrow())
                .usingRecursiveComparison().isEqualTo(expected);
        assertThat(loader.calls).hasValue(1);
        assertThat(cache.diagramStats().hitCount()).isEqualTo(2);
    }

    @Test
    void repeatedShareHitsPreserveAllImmutableFields() {
        DiagramShare share = share(TOKEN, ShareRole.EDIT, NOW.plusSeconds(60));
        Loader<DiagramShare> loader = new Loader<>(share);

        assertThat(cache.findShare(TOKEN, loader)).contains(share);
        assertThat(cache.findShare(TOKEN, loader).orElseThrow())
                .usingRecursiveComparison().isEqualTo(share);
        assertThat(loader.calls).hasValue(1);
        assertThat(cache.shareStats().hitCount()).isEqualTo(1);
    }

    @Test
    void diagramTtlExpiresAtWriteBoundaryAndDoesNotSlideOnHits() {
        Loader<Diagram> loader = new Loader<>(diagram(DIAGRAM_ID, "Old"));
        cache.findDiagram(DIAGRAM_ID, loader);
        loader.value = Optional.of(diagram(DIAGRAM_ID, "Fresh"));

        ticker.advance(TTL.minusNanos(1));
        assertThat(cache.findDiagram(DIAGRAM_ID, loader).orElseThrow().getTitle()).isEqualTo("Old");
        ticker.advance(Duration.ofNanos(1));
        assertThat(cache.findDiagram(DIAGRAM_ID, loader).orElseThrow().getTitle()).isEqualTo("Fresh");
        assertThat(loader.calls).hasValue(2);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shareTtlDoesNotSlideForLegacyOrLongLivedTokens(boolean legacy) {
        Instant expiresAt = legacy ? null : NOW.plusSeconds(60);
        Loader<DiagramShare> loader = new Loader<>(share(TOKEN, ShareRole.EDIT, expiresAt));
        cache.findShare(TOKEN, loader);
        loader.value = Optional.of(share(TOKEN, ShareRole.VIEW, expiresAt));

        ticker.advance(TTL.minusNanos(1));
        assertThat(cache.findShare(TOKEN, loader).orElseThrow().getRole()).isEqualTo(ShareRole.EDIT);
        ticker.advance(Duration.ofNanos(1));
        DiagramShare reloaded = cache.findShare(TOKEN, loader).orElseThrow();
        assertThat(reloaded.getRole()).isEqualTo(ShareRole.VIEW);
        assertThat(reloaded.getExpiresAt()).isEqualTo(expiresAt);
        assertThat(loader.calls).hasValue(2);
    }

    @Test
    void shareLifetimeShortensTtlAtTheExactExpiryBoundary() {
        Loader<DiagramShare> loader = new Loader<>(share(TOKEN, ShareRole.EDIT, NOW.plusSeconds(3)));
        cache.findShare(TOKEN, loader);
        loader.value = Optional.empty();

        ticker.advance(Duration.ofSeconds(3).minusNanos(1));
        assertThat(cache.findShare(TOKEN, loader)).isPresent();
        ticker.advance(Duration.ofNanos(1));
        assertThat(cache.findShare(TOKEN, loader)).isEmpty();
        assertThat(loader.calls).hasValue(2);
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 0})
    void expiredSharesAreRejectedAndNotCachedIncludingExactBoundary(long offsetNanos) {
        Loader<DiagramShare> loader = new Loader<>(share(TOKEN, ShareRole.EDIT, NOW.plusNanos(offsetNanos)));

        assertThat(cache.findShare(TOKEN, loader)).isEmpty();
        assertThat(cache.findShare(TOKEN, loader)).isEmpty();
        assertThat(loader.calls).hasValue(2);

        loader.value = Optional.of(share(TOKEN, ShareRole.VIEW, NOW.plusNanos(1)));
        assertThat(cache.findShare(TOKEN, loader)).isPresent();
        assertThat(cache.findShare(TOKEN, loader)).isPresent();
        assertThat(loader.calls).hasValue(3);
    }

    @Test
    void tokenCacheKeysContainOnlySha256Digests() {
        cache.findShare(TOKEN, () -> Optional.of(share(TOKEN, ShareRole.VIEW, null)));

        // Inspect only the private key representation; do not add a production API exposing tokens.
        Cache<?, ?> shares = (Cache<?, ?>) ReflectionTestUtils.getField(cache, "shares");
        assertThat(shares).isNotNull();
        assertThat(shares.asMap().keySet()).hasSize(1);
        assertThat(shares.asMap().containsKey("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"))
                .isTrue();
        assertThat(shares.asMap().containsKey(TOKEN)).isFalse();
    }

    @Test
    void distinctTokensDoNotShareEntries() {
        DiagramShare first = share("first", ShareRole.EDIT, null);
        DiagramShare second = share("second", ShareRole.VIEW, null);
        cache.findShare("first", () -> Optional.of(first));
        cache.findShare("second", () -> Optional.of(second));

        assertThat(cache.findShare("first", Optional::empty)).contains(first);
        assertThat(cache.findShare("second", Optional::empty)).contains(second);
    }

    @Test
    void diagramWeightBoundEventuallyEvictsWithoutAssumingAnEvictionVictim() {
        ReadCache small = newCache(ReadCacheProperties.Mode.LOCAL, 1_024, 10);
        UUID otherId = UUID.randomUUID();
        small.findDiagram(DIAGRAM_ID, () -> Optional.of(diagram(DIAGRAM_ID, "First")));
        small.findDiagram(otherId, () -> Optional.of(diagram(otherId, "Other")));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(small.diagramStats().evictionCount()).isEqualTo(1));
        // Empty loaders probe residency without adding entries and triggering further eviction.
        long resident = List.of(DIAGRAM_ID, otherId).stream()
                .filter(id -> small.findDiagram(id, Optional::empty).isPresent()).count();
        assertThat(resident).isEqualTo(1);
        assertThat(small.diagramStats().evictionWeight()).isGreaterThan(512);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void oversizedDiagramPayloadOrTitleIsNotRetained(boolean largeTitle) {
        ReadCache small = newCache(ReadCacheProperties.Mode.LOCAL, 1_024, 10);
        Diagram oversized = diagram(DIAGRAM_ID, largeTitle ? "x".repeat(1_024) : "Large");
        if (!largeTitle) {
            oversized.setData(DiagramData.of("{\"nodes\":[{\"label\":\"" + "x".repeat(1_024)
                    + "\"}],\"edges\":[]}"));
        }
        Loader<Diagram> loader = new Loader<>(oversized);
        assertThat(small.findDiagram(DIAGRAM_ID, loader)).isPresent();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(small.diagramStats().evictionCount()).isEqualTo(1));
        assertThat(small.findDiagram(DIAGRAM_ID, loader)).isPresent();
        assertThat(loader.calls).hasValue(2);
    }

    @Test
    void tokenEntryBoundEventuallyEvictsWithoutAssumingAnEvictionVictim() {
        ReadCache small = newCache(ReadCacheProperties.Mode.LOCAL, 16_384, 2);
        List<String> tokens = List.of("first", "second", "third");
        tokens.forEach(token -> small.findShare(token, () -> Optional.of(share(token, ShareRole.VIEW, null))));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(small.shareStats().evictionCount()).isEqualTo(1));
        assertThat(tokens.stream().filter(token -> small.findShare(token, Optional::empty).isPresent()).count())
                .isEqualTo(2);
    }

    @Test
    void disabledModeAlwaysUsesSuppliersAndDoesNotPopulateEitherCache() {
        ReadCache disabled = newCache(ReadCacheProperties.Mode.DISABLED, 16_384, 10);
        Loader<Diagram> diagrams = new Loader<>(diagram(DIAGRAM_ID, "First"));
        Loader<DiagramShare> shares = new Loader<>(share(TOKEN, ShareRole.EDIT, null));
        disabled.findDiagram(DIAGRAM_ID, diagrams);
        disabled.findShare(TOKEN, shares);
        diagrams.value = Optional.of(diagram(DIAGRAM_ID, "Second"));
        shares.value = Optional.of(share(TOKEN, ShareRole.VIEW, null));

        assertThat(disabled.findDiagram(DIAGRAM_ID, diagrams).orElseThrow().getTitle()).isEqualTo("Second");
        assertThat(disabled.findShare(TOKEN, shares).orElseThrow().getRole()).isEqualTo(ShareRole.VIEW);
        assertThat(disabled.findDiagram(DIAGRAM_ID, Optional::empty)).isEmpty();
        assertThat(disabled.findShare(TOKEN, Optional::empty)).isEmpty();
        assertThat(diagrams.calls).hasValue(2);
        assertThat(shares.calls).hasValue(2);
        assertThat(disabled.diagramStats().requestCount()).isZero();
        assertThat(disabled.shareStats().requestCount()).isZero();
    }

    @Test
    void missingDiagramsAndTokensAreNeverNegativelyCached() {
        Loader<Diagram> diagrams = new Loader<>(null);
        Loader<DiagramShare> shares = new Loader<>(null);
        for (int i = 0; i < 2; i++) {
            assertThat(cache.findDiagram(DIAGRAM_ID, diagrams)).isEmpty();
            assertThat(cache.findShare(TOKEN, shares)).isEmpty();
        }
        diagrams.value = Optional.of(diagram(DIAGRAM_ID, "Created"));
        shares.value = Optional.of(share(TOKEN, ShareRole.VIEW, null));

        assertThat(cache.findDiagram(DIAGRAM_ID, diagrams)).isPresent();
        assertThat(cache.findShare(TOKEN, shares)).isPresent();
        assertThat(cache.findDiagram(DIAGRAM_ID, diagrams)).isPresent();
        assertThat(cache.findShare(TOKEN, shares)).isPresent();
        assertThat(diagrams.calls).hasValue(3);
        assertThat(shares.calls).hasValue(3);
    }

    @Test
    void supplierFailuresPropagateWithoutPopulatingCaches() {
        IllegalStateException failure = new IllegalStateException("SELECT failed");
        assertThatThrownBy(() -> cache.findDiagram(DIAGRAM_ID, () -> { throw failure; })).isSameAs(failure);
        assertThatThrownBy(() -> cache.findShare(TOKEN, () -> { throw failure; })).isSameAs(failure);

        assertThat(cache.findDiagram(DIAGRAM_ID, () -> Optional.of(diagram(DIAGRAM_ID, "Recovered"))))
                .isPresent();
        assertThat(cache.findShare(TOKEN, () -> Optional.of(share(TOKEN, ShareRole.VIEW, null)))).isPresent();
    }

    @Test
    void roleDowngradeAndRevocationClearOldShareValues() {
        Loader<DiagramShare> loader = new Loader<>(share(TOKEN, ShareRole.EDIT, null));
        cache.findShare(TOKEN, loader);
        loader.value = Optional.of(share(TOKEN, ShareRole.VIEW, null));
        cache.sharesChanged();
        assertThat(cache.findShare(TOKEN, loader).orElseThrow().getRole()).isEqualTo(ShareRole.VIEW);

        loader.value = Optional.empty();
        cache.sharesChanged();
        assertThat(cache.findShare(TOKEN, loader)).isEmpty();
        assertThat(loader.calls).hasValue(3);
    }

    @Test
    void deletingDiagramClearsItsDiagramAndAllCachedShareEntries() {
        UUID otherId = UUID.randomUUID();
        cache.findDiagram(DIAGRAM_ID, () -> Optional.of(diagram(DIAGRAM_ID, "Deleted")));
        cache.findDiagram(otherId, () -> Optional.of(diagram(otherId, "Unrelated")));
        cache.findShare(TOKEN, () -> Optional.of(share(TOKEN, ShareRole.EDIT, null)));
        cache.findShare("second", () -> Optional.of(share("second", ShareRole.VIEW, null)));

        cache.diagramDeleted(DIAGRAM_ID);

        assertThat(cache.findDiagram(DIAGRAM_ID, Optional::empty)).isEmpty();
        assertThat(cache.findShare(TOKEN, Optional::empty)).isEmpty();
        assertThat(cache.findShare("second", Optional::empty)).isEmpty();
        assertThat(cache.findDiagram(otherId, Optional::empty)).isPresent();
    }

    @Test
    void diagramLoaderRetriesWhenInvalidationCompletesDuringSelect() throws Exception {
        Diagram fresh = diagram(DIAGRAM_ID, "Fresh");
        fresh.setVersion(2);
        InFlightLoader<Diagram> loader = new InFlightLoader<>(diagram(DIAGRAM_ID, "Stale"), Optional.of(fresh));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Optional<Diagram>> result = executor.submit(() -> cache.findDiagram(DIAGRAM_ID, loader));
            assertThat(loader.started.await(5, TimeUnit.SECONDS)).isTrue();
            cache.diagramChanged(DIAGRAM_ID);
            loader.release.countDown();

            assertThat(result.get(5, TimeUnit.SECONDS).orElseThrow()).usingRecursiveComparison().isEqualTo(fresh);
            assertThat(cache.findDiagram(DIAGRAM_ID, loader).orElseThrow())
                    .usingRecursiveComparison().isEqualTo(fresh);
            assertThat(loader.calls).hasValue(2);
        } finally {
            loader.release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shareLoaderRetriesInsteadOfReturningOrRepopulatingOldPermission(boolean revoked) throws Exception {
        Optional<DiagramShare> fresh = revoked ? Optional.empty() : Optional.of(share(TOKEN, ShareRole.VIEW, null));
        InFlightLoader<DiagramShare> loader = new InFlightLoader<>(share(TOKEN, ShareRole.EDIT, null), fresh);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Optional<DiagramShare>> result = executor.submit(() -> cache.findShare(TOKEN, loader));
            assertThat(loader.started.await(5, TimeUnit.SECONDS)).isTrue();
            cache.sharesChanged();
            loader.release.countDown();

            assertThat(result.get(5, TimeUnit.SECONDS)).isEqualTo(fresh);
            assertThat(loader.calls).hasValue(2);
            assertThat(cache.findShare(TOKEN, loader)).isEqualTo(fresh);
            assertThat(loader.calls).hasValue(revoked ? 3 : 2);
        } finally {
            loader.release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void transactionReadsBypassExistingEntriesAndNeverPopulateOrReplaceEntries() {
        UUID uncachedId = UUID.randomUUID();
        cache.findDiagram(DIAGRAM_ID, () -> Optional.of(diagram(DIAGRAM_ID, "Committed")));
        cache.findShare(TOKEN, () -> Optional.of(share(TOKEN, ShareRole.VIEW, null)));
        Loader<Diagram> changedDiagram = new Loader<>(diagram(DIAGRAM_ID, "Uncommitted"));
        Loader<DiagramShare> changedShare = new Loader<>(share(TOKEN, ShareRole.EDIT, null));
        Loader<Diagram> newDiagram = new Loader<>(diagram(uncachedId, "Uncommitted new"));
        Loader<DiagramShare> newShare = new Loader<>(share("new", ShareRole.EDIT, null));
        try {
            beginTransaction();
            for (int i = 0; i < 2; i++) {
                assertThat(cache.findDiagram(DIAGRAM_ID, changedDiagram).orElseThrow().getTitle())
                        .isEqualTo("Uncommitted");
                assertThat(cache.findShare(TOKEN, changedShare).orElseThrow().getRole()).isEqualTo(ShareRole.EDIT);
                assertThat(cache.findDiagram(uncachedId, newDiagram)).isPresent();
                assertThat(cache.findShare("new", newShare)).isPresent();
            }
            completeTransaction(false);
        } finally {
            TransactionSynchronizationManager.clear();
        }

        assertThat(changedDiagram.calls).hasValue(2);
        assertThat(changedShare.calls).hasValue(2);
        assertThat(newDiagram.calls).hasValue(2);
        assertThat(newShare.calls).hasValue(2);
        assertThat(cache.findDiagram(DIAGRAM_ID, Optional::empty).orElseThrow().getTitle()).isEqualTo("Committed");
        assertThat(cache.findShare(TOKEN, Optional::empty).orElseThrow().getRole()).isEqualTo(ShareRole.VIEW);
        assertThat(cache.findDiagram(uncachedId, Optional::empty)).isEmpty();
        assertThat(cache.findShare("new", Optional::empty)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(Change.class)
    void invalidationWaitsForAfterCommitThenClearsAffectedEntries(Change change) {
        warmBothCaches();
        try {
            beginTransaction();
            invalidate(change);
            assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);

            // Suspend only the transaction-active flag to inspect what an outside reader sees.
            TransactionSynchronizationManager.setActualTransactionActive(false);
            assertThat(cache.findDiagram(DIAGRAM_ID, Optional::empty)).isPresent();
            assertThat(cache.findShare(TOKEN, Optional::empty)).isPresent();
            TransactionSynchronizationManager.setActualTransactionActive(true);
            completeTransaction(true);
        } finally {
            TransactionSynchronizationManager.clear();
        }

        assertThat(cache.findDiagram(DIAGRAM_ID, Optional::empty).isPresent()).isEqualTo(change == Change.SHARES);
        assertThat(cache.findShare(TOKEN, Optional::empty).isPresent()).isEqualTo(change == Change.DIAGRAM);
    }

    @ParameterizedTest
    @EnumSource(Change.class)
    void rollbackDoesNotEvictCommittedEntries(Change change) {
        warmBothCaches();
        try {
            beginTransaction();
            invalidate(change);
            completeTransaction(false);
        } finally {
            TransactionSynchronizationManager.clear();
        }

        assertThat(cache.findDiagram(DIAGRAM_ID, Optional::empty)).isPresent();
        assertThat(cache.findShare(TOKEN, Optional::empty)).isPresent();
    }

    private ReadCache newCache(ReadCacheProperties.Mode mode, long maxWeight, long maxTokens) {
        return new ReadCache(new ReadCacheProperties(mode, maxWeight, TTL, maxTokens, TTL), CLOCK, ticker);
    }

    private void warmBothCaches() {
        cache.findDiagram(DIAGRAM_ID, () -> Optional.of(diagram(DIAGRAM_ID, "Committed")));
        cache.findShare(TOKEN, () -> Optional.of(share(TOKEN, ShareRole.VIEW, null)));
    }

    private void invalidate(Change change) {
        switch (change) {
            case DIAGRAM -> cache.diagramChanged(DIAGRAM_ID);
            case SHARES -> cache.sharesChanged();
            case DELETE -> cache.diagramDeleted(DIAGRAM_ID);
        }
    }

    private static void beginTransaction() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    private static void completeTransaction(boolean commit) {
        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        if (commit) {
            synchronizations.forEach(TransactionSynchronization::afterCommit);
        }
        synchronizations.forEach(synchronization -> synchronization.afterCompletion(commit
                ? TransactionSynchronization.STATUS_COMMITTED : TransactionSynchronization.STATUS_ROLLED_BACK));
    }

    private static Diagram diagram(UUID id, String title) {
        return new Diagram(id, title, DiagramType.UML, DiagramData.EMPTY, 1, false,
                OWNER_ID, OWNER_ID, NOW.minusSeconds(60), NOW);
    }

    private static DiagramShare share(String token, ShareRole role, Instant expiresAt) {
        return new DiagramShare(UUID.randomUUID(), DIAGRAM_ID, token, role, NOW, expiresAt);
    }

    private static void mutateEveryField(Diagram diagram) {
        diagram.setId(UUID.randomUUID());
        diagram.setTitle("Mutated");
        diagram.setDiagramType(DiagramType.ERD);
        diagram.setData(DiagramData.of("{\"nodes\":[{\"id\":\"changed\"}],\"edges\":[]}"));
        diagram.setVersion(99);
        diagram.setPublic(true);
        diagram.setOwnerId(UUID.randomUUID());
        diagram.setUpdatedBy(null);
        diagram.setCreatedAt(NOW.plusSeconds(1));
        diagram.setUpdatedAt(NOW.plusSeconds(2));
    }

    private enum Change { DIAGRAM, SHARES, DELETE }

    private static final class FakeTicker implements Ticker {
        private final AtomicLong nanos = new AtomicLong();

        @Override
        public long read() {
            return nanos.get();
        }

        void advance(Duration duration) {
            nanos.addAndGet(duration.toNanos());
        }
    }

    private static final class Loader<T> implements Supplier<Optional<T>> {
        private final AtomicInteger calls = new AtomicInteger();
        private Optional<T> value;

        private Loader(T value) {
            this.value = Optional.ofNullable(value);
        }

        @Override
        public Optional<T> get() {
            calls.incrementAndGet();
            return value;
        }
    }

    private static final class InFlightLoader<T> implements Supplier<Optional<T>> {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger calls = new AtomicInteger();
        private final T stale;
        private final Optional<T> fresh;

        private InFlightLoader(T stale, Optional<T> fresh) {
            this.stale = stale;
            this.fresh = fresh;
        }

        @Override
        public Optional<T> get() {
            if (calls.incrementAndGet() == 1) {
                started.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("Invalidation did not release the in-flight SELECT");
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("In-flight SELECT interrupted", ex);
                }
                return Optional.of(stale);
            }
            return fresh;
        }
    }
}
