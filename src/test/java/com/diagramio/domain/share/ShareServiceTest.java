package com.diagramio.domain.share;

import com.diagramio.domain.access.Caller;
import com.diagramio.domain.access.ShareRole;
import com.diagramio.domain.exception.ForbiddenException;
import com.diagramio.domain.exception.InvalidInputException;
import com.diagramio.domain.exception.NotFoundException;
import com.diagramio.domain.exception.UnauthorizedException;
import com.diagramio.domain.diagram.Diagram;
import com.diagramio.domain.diagram.DiagramData;
import com.diagramio.domain.diagram.DiagramRepository;
import com.diagramio.domain.diagram.DiagramType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShareServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");
    private static final Duration DEFAULT_TTL = Duration.ofDays(7);
    private static final Instant EXPIRES_AT = NOW.plus(DEFAULT_TTL);

    @Mock
    private DiagramShareRepository shareRepository;

    @Mock
    private DiagramRepository diagramRepository;

    private ShareService shareService;

    private final UUID ownerId = UUID.randomUUID();
    private final UUID otherUserId = UUID.randomUUID();
    private final UUID diagramId = UUID.randomUUID();

    private Diagram sampleDiagram;

    @BeforeEach
    void setUp() {
        shareService = new ShareService(shareRepository, diagramRepository, Clock.fixed(NOW, ZoneOffset.UTC),
                DEFAULT_TTL);
        sampleDiagram = new Diagram(
                diagramId,
                "Sơ đồ lớp BTL",
                DiagramType.UML,
                DiagramData.fromStorage("{\"nodes\":[], \"edges\":[]}"),
                1,
                false,
                ownerId,
                ownerId,
                NOW,
                NOW
        );
    }

    @Test
    @DisplayName("Chủ sở hữu có thể lấy danh sách liên kết chia sẻ")
    void getShares_OwnerSuccess() {
        when(diagramRepository.findById(diagramId)).thenReturn(Optional.of(sampleDiagram));
        DiagramShare share = new DiagramShare(UUID.randomUUID(), diagramId, "token123", ShareRole.VIEW, NOW, EXPIRES_AT);
        when(shareRepository.findByDiagramId(diagramId)).thenReturn(List.of(share));

        List<DiagramShare> result = shareService.getShares(diagramId, Caller.user(ownerId));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getShareToken()).isEqualTo("token123");
    }

    @Test
    @DisplayName("Người dùng khác cố lấy danh sách chia sẻ sẽ bị 403 Forbidden")
    void getShares_ForbiddenForNonOwner() {
        when(diagramRepository.findById(diagramId)).thenReturn(Optional.of(sampleDiagram));

        assertThatThrownBy(() -> shareService.getShares(diagramId, Caller.user(otherUserId)))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Only the diagram owner");
    }

    @Test
    @DisplayName("Người cầm link edit vẫn không được quản lý liên kết (chỉ owner)")
    void createShare_ForbiddenForEditShareToken() {
        when(diagramRepository.findById(diagramId)).thenReturn(Optional.of(sampleDiagram));

        assertThatThrownBy(() -> shareService.createShare(diagramId, ShareRole.EDIT, null,
                Caller.shareToken(diagramId, ShareRole.EDIT)))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("Sơ đồ không tồn tại sẽ bị 404 NotFound")
    void getShares_DiagramNotFound() {
        when(diagramRepository.findById(diagramId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> shareService.getShares(diagramId, Caller.user(ownerId)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("Chủ sở hữu tạo liên kết chia sẻ thành công với quyền view")
    void createShare_Success() {
        when(diagramRepository.findById(diagramId)).thenReturn(Optional.of(sampleDiagram));
        when(shareRepository.save(any(DiagramShare.class))).thenAnswer(invocation -> invocation.getArgument(0));

        DiagramShare created = shareService.createShare(diagramId, ShareRole.VIEW, null, Caller.user(ownerId));

        assertThat(created).isNotNull();
        assertThat(created.getDiagramId()).isEqualTo(diagramId);
        assertThat(created.getRole()).isEqualTo(ShareRole.VIEW);
        assertThat(created.getShareToken()).isNotBlank();
        assertThat(created.getCreatedAt()).isEqualTo(NOW);
        assertThat(created.getExpiresAt()).isEqualTo(EXPIRES_AT);
        verify(shareRepository).save(created);
    }


    @Test
    @DisplayName("Cập nhật quyền liên kết chia sẻ thành công (view -> edit)")
    void updateShareRole_Success() {
        UUID shareId = UUID.randomUUID();
        DiagramShare storedAfterUpdate = new DiagramShare(shareId, diagramId, "token123", ShareRole.EDIT, NOW, EXPIRES_AT);

        when(diagramRepository.findById(diagramId)).thenReturn(Optional.of(sampleDiagram));
        when(shareRepository.updateRole(shareId, diagramId, ShareRole.EDIT)).thenReturn(true);
        when(shareRepository.findById(shareId)).thenReturn(Optional.of(storedAfterUpdate));

        DiagramShare updated = shareService.updateShareRole(diagramId, shareId, ShareRole.EDIT, Caller.user(ownerId));

        assertThat(updated.getRole()).isEqualTo(ShareRole.EDIT);
        assertThat(updated.getShareToken()).isEqualTo("token123");
        assertThat(updated.getExpiresAt()).isEqualTo(EXPIRES_AT);
        verify(shareRepository, never()).save(any(DiagramShare.class));
    }

    @Test
    @DisplayName("Đổi quyền link vừa bị thu hồi trả 404, không tạo lại link")
    void updateShareRole_RevokedLink_NotFoundAndNotRecreated() {
        UUID shareId = UUID.randomUUID();
        when(diagramRepository.findById(diagramId)).thenReturn(Optional.of(sampleDiagram));
        when(shareRepository.updateRole(shareId, diagramId, ShareRole.EDIT)).thenReturn(false);

        assertThatThrownBy(() -> shareService.updateShareRole(diagramId, shareId, ShareRole.EDIT, Caller.user(ownerId)))
                .isInstanceOf(NotFoundException.class);
        verify(shareRepository, never()).save(any(DiagramShare.class));
    }

    @Test
    @DisplayName("Thu hồi liên kết chia sẻ thành công")
    void revokeShare_Success() {
        UUID shareId = UUID.randomUUID();
        DiagramShare oldShare = new DiagramShare(shareId, diagramId, "token123", ShareRole.VIEW, NOW, EXPIRES_AT);

        when(diagramRepository.findById(diagramId)).thenReturn(Optional.of(sampleDiagram));
        when(shareRepository.findById(shareId)).thenReturn(Optional.of(oldShare));

        shareService.revokeShare(diagramId, shareId, Caller.user(ownerId));

        verify(shareRepository).deleteById(shareId);
    }

    @Test
    @DisplayName("Truy xuất cấu trúc sơ đồ qua token hợp lệ (Public)")
    void getDiagramByShareToken_Success() {
        String token = "valid_token_xyz";
        DiagramShare share = new DiagramShare(UUID.randomUUID(), diagramId, token, ShareRole.EDIT, NOW, EXPIRES_AT);

        when(shareRepository.findByShareToken(token)).thenReturn(Optional.of(share));
        when(diagramRepository.findById(diagramId)).thenReturn(Optional.of(sampleDiagram));

        SharedDiagramView view = shareService.getDiagramByShareToken(token);

        assertThat(view).isNotNull();
        assertThat(view.getDiagramId()).isEqualTo(diagramId);
        assertThat(view.getTitle()).isEqualTo("Sơ đồ lớp BTL");
        assertThat(view.getRole()).isEqualTo(ShareRole.EDIT);
    }

    @Test
    @DisplayName("Truy xuất sơ đồ bằng token không tồn tại sẽ bị 404 NotFound")
    void getDiagramByShareToken_NotFound() {
        when(shareRepository.findByShareToken("invalid_token")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> shareService.getDiagramByShareToken("invalid_token"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("does not exist");
    }

    @Test
    @DisplayName("Token X-Share-Token hợp lệ được đổi thành Caller có quyền trên đúng sơ đồ")
    void resolveShareToken_ValidToken() {
        DiagramShare share = new DiagramShare(UUID.randomUUID(), diagramId, "edit_token", ShareRole.EDIT, NOW, EXPIRES_AT);
        when(shareRepository.findByShareToken("edit_token")).thenReturn(Optional.of(share));

        assertThat(shareService.resolveShareToken(" edit_token "))
                .contains(Caller.shareToken(diagramId, ShareRole.EDIT));
    }

    @Test
    @DisplayName("Token không tồn tại hoặc đã thu hồi thì không có Caller")
    void resolveShareToken_UnknownOrBlank() {
        when(shareRepository.findByShareToken("revoked")).thenReturn(Optional.empty());

        assertThat(shareService.resolveShareToken("revoked")).isEmpty();
        assertThat(shareService.resolveShareToken("  ")).isEmpty();
        assertThat(shareService.resolveShareToken(null)).isEmpty();
    }

    @Test
    void createShareUsesConfiguredDefaultTtl() {
        ShareService configured = new ShareService(shareRepository, diagramRepository,
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofHours(3));
        when(diagramRepository.findById(diagramId)).thenReturn(Optional.of(sampleDiagram));
        when(shareRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        DiagramShare created = configured.createShare(diagramId, ShareRole.EDIT, null, Caller.user(ownerId));

        assertThat(created.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofHours(3)));
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 3600000000000L, 1209600000000000L})
    void createSharePreservesCustomFutureExpiry(long nanos) {
        Instant expiresAt = NOW.plusNanos(nanos);
        when(diagramRepository.findById(diagramId)).thenReturn(Optional.of(sampleDiagram));
        when(shareRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        DiagramShare created = shareService.createShare(diagramId, ShareRole.EDIT, expiresAt, Caller.user(ownerId));

        assertThat(created.getCreatedAt()).isEqualTo(NOW);
        assertThat(created.getExpiresAt()).isEqualTo(expiresAt);
        verify(shareRepository).save(created);
    }

    @ParameterizedTest
    @ValueSource(longs = {-86400, -1, 0})
    void createShareRejectsPastOrCurrentExpiryWithoutWriting(long seconds) {
        when(diagramRepository.findById(diagramId)).thenReturn(Optional.of(sampleDiagram));

        assertThatThrownBy(() -> shareService.createShare(diagramId, ShareRole.VIEW, NOW.plusSeconds(seconds),
                Caller.user(ownerId)))
                .isInstanceOf(InvalidInputException.class)
                .hasMessage("expiresAt must be in the future");
        verifyNoInteractions(shareRepository);
    }

    @ParameterizedTest
    @EnumSource(ShareRole.class)
    void createShareDeniedForViewersAndEditorsWithOrWithoutAccount(ShareRole role) {
        when(diagramRepository.findById(diagramId)).thenReturn(Optional.of(sampleDiagram));

        for (Caller caller : List.of(Caller.shareToken(diagramId, role), new Caller(otherUserId, diagramId, role))) {
            assertThatThrownBy(() -> shareService.createShare(diagramId, ShareRole.VIEW, EXPIRES_AT, caller))
                    .isInstanceOf(ForbiddenException.class);
        }
        verifyNoInteractions(shareRepository);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void createShareDeniedForAnonymousAndOtherUsersEvenOnPublicDiagram(boolean isPublic) {
        sampleDiagram.setPublic(isPublic);
        when(diagramRepository.findById(diagramId)).thenReturn(Optional.of(sampleDiagram));

        assertThatThrownBy(() -> shareService.createShare(diagramId, ShareRole.VIEW, null, Caller.anonymous()))
                .isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> shareService.createShare(diagramId, ShareRole.VIEW, null, Caller.user(otherUserId)))
                .isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(shareRepository);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void defaultTtlMustBePositive(long seconds) {
        assertThatThrownBy(() -> new ShareService(shareRepository, diagramRepository,
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(seconds)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("defaultLinkTtl must be positive");
    }

    @Test
    void isExpiredUsesExactBoundaryAndPreservesLegacyNull() {
        DiagramShare finite = new DiagramShare(UUID.randomUUID(), diagramId, "finite", ShareRole.VIEW, NOW, EXPIRES_AT);
        DiagramShare legacy = new DiagramShare(UUID.randomUUID(), diagramId, "legacy", ShareRole.VIEW, NOW, null);

        assertThat(finite.isExpired(EXPIRES_AT.minusNanos(1))).isFalse();
        assertThat(finite.isExpired(EXPIRES_AT)).isTrue();
        assertThat(finite.isExpired(EXPIRES_AT.plusNanos(1))).isTrue();
        assertThat(legacy.getExpiresAt()).isNull();
        assertThat(legacy.isExpired(Instant.MAX)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void reusedShareIsRecheckedOnEveryUrlAndHeaderRequest(boolean isPublic) {
        AtomicReference<Instant> now = new AtomicReference<>(EXPIRES_AT.minusNanos(1));
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenAnswer(invocation -> now.get());
        ShareService service = new ShareService(shareRepository, diagramRepository, clock, DEFAULT_TTL);
        DiagramShare share = new DiagramShare(UUID.randomUUID(), diagramId, "reused", ShareRole.EDIT, NOW, EXPIRES_AT);
        sampleDiagram.setPublic(isPublic);
        when(shareRepository.findByShareToken("reused")).thenReturn(Optional.of(share));
        when(diagramRepository.findById(diagramId)).thenReturn(Optional.of(sampleDiagram));

        assertThat(service.getDiagramByShareToken(" reused ").getDiagramId()).isEqualTo(diagramId);
        assertThat(service.resolveShareToken(" reused ")).contains(Caller.shareToken(diagramId, ShareRole.EDIT));

        now.set(EXPIRES_AT);
        assertThatThrownBy(() -> service.getDiagramByShareToken("reused"))
                .isInstanceOf(NotFoundException.class);
        assertThat(service.resolveShareToken("reused")).isEmpty();

        now.set(EXPIRES_AT.plusNanos(1));
        assertThatThrownBy(() -> service.getDiagramByShareToken("reused"))
                .isInstanceOf(NotFoundException.class);
        assertThat(service.resolveShareToken("reused")).isEmpty();
        verify(diagramRepository, times(1)).findById(diagramId);
        verify(shareRepository, times(6)).findByShareToken("reused");
    }

    @Test
    void legacyLinkRemainsUsableIndefinitelyInBothPaths() {
        ShareService service = new ShareService(shareRepository, diagramRepository,
                Clock.fixed(Instant.MAX, ZoneOffset.UTC), DEFAULT_TTL);
        DiagramShare legacy = new DiagramShare(UUID.randomUUID(), diagramId, "legacy", ShareRole.VIEW, NOW, null);
        when(shareRepository.findByShareToken("legacy")).thenReturn(Optional.of(legacy));
        when(diagramRepository.findById(diagramId)).thenReturn(Optional.of(sampleDiagram));

        assertThat(service.getDiagramByShareToken("legacy").getDiagramId()).isEqualTo(diagramId);
        assertThat(service.resolveShareToken("legacy")).contains(Caller.shareToken(diagramId, ShareRole.VIEW));
    }

    @Test
    void ownerListIncludesExpiredActiveAndLegacyLinks() {
        DiagramShare expired = new DiagramShare(UUID.randomUUID(), diagramId, "expired", ShareRole.VIEW, NOW, NOW);
        DiagramShare active = new DiagramShare(UUID.randomUUID(), diagramId, "active", ShareRole.VIEW, NOW, EXPIRES_AT);
        DiagramShare legacy = new DiagramShare(UUID.randomUUID(), diagramId, "legacy", ShareRole.VIEW, NOW, null);
        when(diagramRepository.findById(diagramId)).thenReturn(Optional.of(sampleDiagram));
        when(shareRepository.findByDiagramId(diagramId)).thenReturn(List.of(expired, active, legacy));

        assertThat(shareService.getShares(diagramId, Caller.user(ownerId))).containsExactly(expired, active, legacy);
    }

    @Test
    void roleChangesNeverRenewExpiredOrLegacyLinks() {
        when(diagramRepository.findById(diagramId)).thenReturn(Optional.of(sampleDiagram));
        for (Instant expiresAt : new Instant[]{NOW.minusSeconds(1), null}) {
            UUID shareId = UUID.randomUUID();
            DiagramShare stored = new DiagramShare(shareId, diagramId, "token", ShareRole.EDIT, NOW, expiresAt);
            when(shareRepository.updateRole(shareId, diagramId, ShareRole.EDIT)).thenReturn(true);
            when(shareRepository.findById(shareId)).thenReturn(Optional.of(stored));

            DiagramShare updated = shareService.updateShareRole(diagramId, shareId, ShareRole.EDIT, Caller.user(ownerId));

            assertThat(updated.getExpiresAt()).isEqualTo(expiresAt);
            assertThat(updated.isExpired(NOW)).isEqualTo(expiresAt != null);
        }
        verify(shareRepository, never()).save(any());
    }
}
