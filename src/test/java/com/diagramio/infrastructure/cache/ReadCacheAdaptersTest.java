package com.diagramio.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.diagramio.domain.access.ShareRole;
import com.diagramio.domain.diagram.Diagram;
import com.diagramio.domain.diagram.DiagramData;
import com.diagramio.domain.diagram.DiagramType;
import com.diagramio.domain.exception.OptimisticLockConflictException;
import com.diagramio.domain.share.DiagramShare;
import com.diagramio.infrastructure.diagram.DiagramEntity;
import com.diagramio.infrastructure.diagram.DiagramJpaRepository;
import com.diagramio.infrastructure.diagram.DiagramMapper;
import com.diagramio.infrastructure.diagram.DiagramRepositoryAdapter;
import com.diagramio.infrastructure.share.DiagramShareEntity;
import com.diagramio.infrastructure.share.DiagramShareJpaRepository;
import com.diagramio.infrastructure.share.DiagramShareMapper;
import com.diagramio.infrastructure.share.DiagramShareRepositoryAdapter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;

class ReadCacheAdaptersTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final UUID DIAGRAM_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OWNER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID SHARE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String TOKEN = "share-token";
    private static final DiagramData UPDATED_DATA = DiagramData.of("{\"nodes\":[{\"id\":\"new\"}],\"edges\":[]}");

    private final ReadCache cache = new ReadCache(new ReadCacheProperties(ReadCacheProperties.Mode.LOCAL,
            16_384, Duration.ofMinutes(1), 10, Duration.ofMinutes(1)), Clock.fixed(NOW, ZoneOffset.UTC), () -> 0L);
    private final DiagramJpaRepository diagramJpa = mock(DiagramJpaRepository.class);
    private final DiagramShareJpaRepository shareJpa = mock(DiagramShareJpaRepository.class);
    private final DiagramMapper diagramMapper = new DiagramMapper();
    private final DiagramShareMapper shareMapper = new DiagramShareMapper();
    private final DiagramRepositoryAdapter diagrams = new DiagramRepositoryAdapter(diagramJpa, diagramMapper, cache);
    private final DiagramShareRepositoryAdapter shares = new DiagramShareRepositoryAdapter(shareJpa, shareMapper, cache);

    @Test
    void repeatedAdapterReadsHitCacheAndDoNotExposeMappedEntityOrDiagramMutations() {
        DiagramEntity stored = diagramMapper.toEntity(diagram("Stored"));
        DiagramShare originalShare = share(ShareRole.VIEW);
        DiagramShareEntity storedShare = shareMapper.toEntity(originalShare);
        when(diagramJpa.findById(DIAGRAM_ID)).thenReturn(Optional.of(stored));
        when(shareJpa.findByShareToken(TOKEN)).thenReturn(Optional.of(storedShare));

        Diagram first = diagrams.findById(DIAGRAM_ID).orElseThrow();
        assertThat(shares.findByShareToken(TOKEN).orElseThrow()).usingRecursiveComparison().isEqualTo(originalShare);
        first.setTitle("Caller mutation");
        first.setData(UPDATED_DATA);
        stored.setTitle("Entity mutation");
        stored.setData(UPDATED_DATA.json());
        storedShare.setRole(ShareRole.EDIT.value());
        storedShare.setExpiresAt(NOW.minusSeconds(1));

        assertThat(diagrams.findById(DIAGRAM_ID).orElseThrow()).usingRecursiveComparison().isEqualTo(diagram("Stored"));
        assertThat(shares.findByShareToken(TOKEN).orElseThrow()).usingRecursiveComparison().isEqualTo(originalShare);
        verify(diagramJpa).findById(DIAGRAM_ID);
        verify(shareJpa).findByShareToken(TOKEN);
    }

    @Test
    void diagramSaveInvalidatesOnlySavedDiagramAndReloadsPersistedContent() {
        Diagram original = diagram("Original");
        Diagram persisted = diagram("Saved");
        persisted.setData(UPDATED_DATA);
        persisted.setVersion(2);
        persisted.setPublic(true);
        UUID otherId = UUID.randomUUID();
        Diagram unrelated = diagram("Unrelated");
        unrelated.setId(otherId);
        when(diagramJpa.findById(DIAGRAM_ID)).thenReturn(Optional.of(diagramMapper.toEntity(original)),
                Optional.of(diagramMapper.toEntity(persisted)));
        when(diagramJpa.findById(otherId)).thenReturn(Optional.of(diagramMapper.toEntity(unrelated)));
        when(diagramJpa.saveAndFlush(any(DiagramEntity.class))).thenReturn(diagramMapper.toEntity(persisted));
        warmShare();
        diagrams.findById(otherId);
        Diagram changed = diagrams.findById(DIAGRAM_ID).orElseThrow();
        changed.setTitle("Saved");
        changed.setData(UPDATED_DATA);
        changed.setPublic(true);

        assertThat(diagrams.save(changed)).usingRecursiveComparison().isEqualTo(persisted);
        assertThat(diagrams.findById(DIAGRAM_ID).orElseThrow()).usingRecursiveComparison().isEqualTo(persisted);
        assertThat(diagrams.findById(otherId).orElseThrow()).usingRecursiveComparison().isEqualTo(unrelated);
        assertThat(shares.findByShareToken(TOKEN).orElseThrow().getRole()).isEqualTo(ShareRole.EDIT);
        ArgumentCaptor<DiagramEntity> written = ArgumentCaptor.forClass(DiagramEntity.class);
        verify(diagramJpa).saveAndFlush(written.capture());
        assertThat(diagramMapper.toDomain(written.getValue())).usingRecursiveComparison().isEqualTo(changed);
        verify(diagramJpa, times(2)).findById(DIAGRAM_ID);
        verify(diagramJpa).findById(otherId);
        verify(shareJpa).findByShareToken(TOKEN);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedDiagramSaveNeitherEvictsNorLeaksMutatedContent(boolean optimisticConflict) {
        Diagram original = diagram("Committed");
        when(diagramJpa.findById(DIAGRAM_ID)).thenReturn(Optional.of(diagramMapper.toEntity(original)));
        RuntimeException failure = optimisticConflict
                ? new OptimisticLockingFailureException("Concurrent update")
                : new DataIntegrityViolationException("Write failed");
        when(diagramJpa.saveAndFlush(any(DiagramEntity.class))).thenThrow(failure);
        Diagram changed = diagrams.findById(DIAGRAM_ID).orElseThrow();
        changed.setTitle("Must not leak");
        changed.setData(UPDATED_DATA);
        changed.setPublic(true);
        changed.setVersion(99);

        if (optimisticConflict) {
            assertThatThrownBy(() -> diagrams.save(changed)).isInstanceOf(OptimisticLockConflictException.class);
        } else {
            assertThatThrownBy(() -> diagrams.save(changed)).isSameAs(failure);
        }

        assertThat(diagrams.findById(DIAGRAM_ID).orElseThrow()).usingRecursiveComparison().isEqualTo(original);
        verify(diagramJpa).saveAndFlush(any(DiagramEntity.class));
        // Conflict translation deliberately makes one additional, uncached version lookup.
        verify(diagramJpa, times(optimisticConflict ? 2 : 1)).findById(DIAGRAM_ID);
    }

    @Test
    void shareSaveInvalidatesTokenValuesAndPreservesExpiryThroughRealMapper() {
        DiagramShare downgraded = share(ShareRole.VIEW);
        warmDiagram();
        warmShare();
        when(shareJpa.save(any(DiagramShareEntity.class))).thenReturn(shareMapper.toEntity(downgraded));
        when(shareJpa.findByShareToken(TOKEN)).thenReturn(Optional.of(shareMapper.toEntity(downgraded)));

        assertThat(shares.save(downgraded)).usingRecursiveComparison().isEqualTo(downgraded);
        assertThat(shares.findByShareToken(TOKEN).orElseThrow()).usingRecursiveComparison().isEqualTo(downgraded);
        assertThat(diagrams.findById(DIAGRAM_ID)).isPresent();
        ArgumentCaptor<DiagramShareEntity> written = ArgumentCaptor.forClass(DiagramShareEntity.class);
        verify(shareJpa).save(written.capture());
        assertThat(shareMapper.toDomain(written.getValue())).usingRecursiveComparison().isEqualTo(downgraded);
        verify(shareJpa, times(2)).findByShareToken(TOKEN);
        verify(diagramJpa).findById(DIAGRAM_ID);
    }

    @Test
    void successfulRoleDowngradeInvalidatesEditorPermission() {
        warmShare();
        when(shareJpa.updateRole(SHARE_ID, DIAGRAM_ID, ShareRole.VIEW.value())).thenReturn(1);
        when(shareJpa.findByShareToken(TOKEN)).thenReturn(Optional.of(shareMapper.toEntity(share(ShareRole.VIEW))));

        assertThat(shares.updateRole(SHARE_ID, DIAGRAM_ID, ShareRole.VIEW)).isTrue();
        assertThat(shares.findByShareToken(TOKEN).orElseThrow().getRole()).isEqualTo(ShareRole.VIEW);
        verify(shareJpa).updateRole(SHARE_ID, DIAGRAM_ID, ShareRole.VIEW.value());
        verify(shareJpa, times(2)).findByShareToken(TOKEN);
    }

    @Test
    void roleUpdateAffectingNoRowsDoesNotInvalidateExistingPermission() {
        warmShare();
        when(shareJpa.updateRole(SHARE_ID, DIAGRAM_ID, ShareRole.VIEW.value())).thenReturn(0);

        assertThat(shares.updateRole(SHARE_ID, DIAGRAM_ID, ShareRole.VIEW)).isFalse();
        assertThat(shares.findByShareToken(TOKEN).orElseThrow().getRole()).isEqualTo(ShareRole.EDIT);
        verify(shareJpa).updateRole(SHARE_ID, DIAGRAM_ID, ShareRole.VIEW.value());
        verify(shareJpa).findByShareToken(TOKEN);
    }

    @Test
    void shareDeletionRevokesCachedPermission() {
        warmShare();
        warmDiagram();
        when(shareJpa.findByShareToken(TOKEN)).thenReturn(Optional.empty());

        shares.deleteById(SHARE_ID);

        assertThat(shares.findByShareToken(TOKEN)).isEmpty();
        assertThat(diagrams.findById(DIAGRAM_ID)).isPresent();
        verify(shareJpa).deleteById(SHARE_ID);
        verify(shareJpa, times(2)).findByShareToken(TOKEN);
        verify(diagramJpa).findById(DIAGRAM_ID);
    }

    @ParameterizedTest
    @EnumSource(ShareWrite.class)
    void failedShareWritesDoNotEvictCommittedPermission(ShareWrite write) {
        warmShare();
        DataIntegrityViolationException failure = new DataIntegrityViolationException("Write failed");
        switch (write) {
            case SAVE -> when(shareJpa.save(any(DiagramShareEntity.class))).thenThrow(failure);
            case UPDATE -> when(shareJpa.updateRole(SHARE_ID, DIAGRAM_ID, ShareRole.VIEW.value())).thenThrow(failure);
            case DELETE -> doThrow(failure).when(shareJpa).deleteById(SHARE_ID);
        }

        assertThatThrownBy(() -> {
            switch (write) {
                case SAVE -> shares.save(share(ShareRole.VIEW));
                case UPDATE -> shares.updateRole(SHARE_ID, DIAGRAM_ID, ShareRole.VIEW);
                case DELETE -> shares.deleteById(SHARE_ID);
            }
        }).isSameAs(failure);

        assertThat(shares.findByShareToken(TOKEN).orElseThrow().getRole()).isEqualTo(ShareRole.EDIT);
        verify(shareJpa).findByShareToken(TOKEN);
    }

    @Test
    void diagramDeletionInvalidatesDiagramAndCascadedShareTokens() {
        warmDiagram();
        warmShare();
        String secondToken = "second-share";
        DiagramShare secondShare = new DiagramShare(UUID.randomUUID(), DIAGRAM_ID, secondToken,
                ShareRole.VIEW, NOW, null);
        when(shareJpa.findByShareToken(secondToken)).thenReturn(Optional.of(shareMapper.toEntity(secondShare)));
        shares.findByShareToken(secondToken);
        when(diagramJpa.findById(DIAGRAM_ID)).thenReturn(Optional.empty());
        when(shareJpa.findByShareToken(TOKEN)).thenReturn(Optional.empty());
        when(shareJpa.findByShareToken(secondToken)).thenReturn(Optional.empty());

        diagrams.deleteById(DIAGRAM_ID);

        assertThat(diagrams.findById(DIAGRAM_ID)).isEmpty();
        assertThat(shares.findByShareToken(TOKEN)).isEmpty();
        assertThat(shares.findByShareToken(secondToken)).isEmpty();
        verify(diagramJpa).deleteById(DIAGRAM_ID);
        verify(diagramJpa, times(2)).findById(DIAGRAM_ID);
        verify(shareJpa, times(2)).findByShareToken(TOKEN);
        verify(shareJpa, times(2)).findByShareToken(secondToken);
    }

    @Test
    void failedDiagramDeletionLeavesBothDiagramAndShareCachesUntouched() {
        warmDiagram();
        warmShare();
        DataIntegrityViolationException failure = new DataIntegrityViolationException("Delete failed");
        doThrow(failure).when(diagramJpa).deleteById(DIAGRAM_ID);

        assertThatThrownBy(() -> diagrams.deleteById(DIAGRAM_ID)).isSameAs(failure);

        assertThat(diagrams.findById(DIAGRAM_ID)).isPresent();
        assertThat(shares.findByShareToken(TOKEN)).isPresent();
        verify(diagramJpa).deleteById(DIAGRAM_ID);
        verify(diagramJpa).findById(DIAGRAM_ID);
        verify(shareJpa).findByShareToken(TOKEN);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void realShareMapperAllowsLegacyButRejectsExpiredTokens(boolean legacy) {
        DiagramShare share = new DiagramShare(SHARE_ID, DIAGRAM_ID, TOKEN, ShareRole.VIEW,
                NOW.minusSeconds(60), legacy ? null : NOW);
        when(shareJpa.findByShareToken(TOKEN)).thenReturn(Optional.of(shareMapper.toEntity(share)));

        assertThat(shares.findByShareToken(TOKEN).isPresent()).isEqualTo(legacy);
        assertThat(shares.findByShareToken(TOKEN).isPresent()).isEqualTo(legacy);
        verify(shareJpa, times(legacy ? 1 : 2)).findByShareToken(TOKEN);
    }

    private void warmDiagram() {
        when(diagramJpa.findById(DIAGRAM_ID)).thenReturn(Optional.of(diagramMapper.toEntity(diagram("Committed"))));
        diagrams.findById(DIAGRAM_ID).orElseThrow();
    }

    private void warmShare() {
        when(shareJpa.findByShareToken(TOKEN)).thenReturn(Optional.of(shareMapper.toEntity(share(ShareRole.EDIT))));
        shares.findByShareToken(TOKEN).orElseThrow();
    }

    private static Diagram diagram(String title) {
        return new Diagram(DIAGRAM_ID, title, DiagramType.UML, DiagramData.EMPTY, 1, false,
                OWNER_ID, OWNER_ID, NOW.minusSeconds(60), NOW);
    }

    private static DiagramShare share(ShareRole role) {
        return new DiagramShare(SHARE_ID, DIAGRAM_ID, TOKEN, role, NOW, NOW.plusSeconds(120));
    }

    private enum ShareWrite { SAVE, UPDATE, DELETE }
}
