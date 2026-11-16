package com.diagramio.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.diagramio.domain.access.ShareRole;
import com.diagramio.domain.share.DiagramShare;
import com.diagramio.infrastructure.share.DiagramShareEntity;
import com.diagramio.infrastructure.share.DiagramShareMapper;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class DiagramShareMapperTest {

    private final DiagramShareMapper mapper = new DiagramShareMapper();

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"2026-06-08T12:00:00Z", "2020-01-01T00:00:00Z"})
    void roundTripPreservesFiniteExpiredAndLegacyExpiry(String expiry) {
        Instant expiresAt = expiry == null ? null : Instant.parse(expiry);
        DiagramShare share = new DiagramShare(UUID.randomUUID(), UUID.randomUUID(), "token", ShareRole.EDIT,
                Instant.parse("2020-01-01T00:00:00Z"), expiresAt);

        DiagramShareEntity entity = mapper.toEntity(share);
        DiagramShare restored = mapper.toDomain(entity);

        assertThat(entity.getExpiresAt()).isEqualTo(expiresAt);
        assertThat(restored.getExpiresAt()).isEqualTo(expiresAt);
        assertThat(restored).usingRecursiveComparison().isEqualTo(share);
    }
}
