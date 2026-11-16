package com.diagramio.infrastructure.share;

import com.diagramio.domain.access.ShareRole;
import com.diagramio.domain.share.DiagramShare;
import com.diagramio.domain.share.DiagramShareRepository;
import com.diagramio.infrastructure.cache.ReadCache;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
public class DiagramShareRepositoryAdapter implements DiagramShareRepository {

    private final DiagramShareJpaRepository jpaRepository;
    private final DiagramShareMapper mapper;
    private final ReadCache cache;

    public DiagramShareRepositoryAdapter(DiagramShareJpaRepository jpaRepository, DiagramShareMapper mapper,
                                         ReadCache cache) {
        this.jpaRepository = jpaRepository;
        this.mapper = mapper;
        this.cache = cache;
    }

    @Override
    public List<DiagramShare> findByDiagramId(UUID diagramId) {
        return jpaRepository.findByDiagramId(diagramId)
                .stream()
                .map(mapper::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public Optional<DiagramShare> findById(UUID id) {
        return jpaRepository.findById(id).map(mapper::toDomain);
    }

    @Override
    public Optional<DiagramShare> findByShareToken(String shareToken) {
        return cache.findShare(shareToken, () -> jpaRepository.findByShareToken(shareToken).map(mapper::toDomain));
    }

    @Override
    public DiagramShare save(DiagramShare share) {
        DiagramShareEntity entity = mapper.toEntity(share);
        DiagramShareEntity saved = jpaRepository.save(entity);
        cache.sharesChanged();
        return mapper.toDomain(saved);
    }

    @Override
    public boolean updateRole(UUID shareId, UUID diagramId, ShareRole role) {
        boolean updated = jpaRepository.updateRole(shareId, diagramId, role.value()) == 1;
        if (updated) {
            cache.sharesChanged();
        }
        return updated;
    }

    @Override
    public void deleteById(UUID id) {
        jpaRepository.deleteById(id);
        cache.sharesChanged();
    }

    @Override
    public boolean existsByShareToken(String shareToken) {
        return jpaRepository.existsByShareToken(shareToken);
    }
}
