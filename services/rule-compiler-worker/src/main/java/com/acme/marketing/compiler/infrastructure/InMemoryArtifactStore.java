package com.acme.marketing.compiler.infrastructure;

import com.acme.marketing.compiler.application.ArtifactStore;
import com.acme.marketing.contracts.artifact.ArtifactBundle;
import com.acme.marketing.platform.error.ConflictException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryArtifactStore implements ArtifactStore {
    private final ConcurrentHashMap<String, ArtifactBundle> artifacts = new ConcurrentHashMap<>();

    @Override
    public ArtifactBundle putIfAbsent(ArtifactBundle artifact) {
        ArtifactBundle stored = artifacts.computeIfAbsent(
                artifact.tenantId() + ':' + artifact.artifactId(), ignored -> artifact);
        if (!stored.checksum().equals(artifact.checksum())
                || !stored.sourceDigest().equals(artifact.sourceDigest())
                || !stored.signature().equals(artifact.signature())) {
            throw new ConflictException("ARTIFACT_ID_COLLISION", "artifact identity collided");
        }
        return stored;
    }

    @Override
    public Optional<ArtifactBundle> find(String tenantId, String artifactId) {
        return Optional.ofNullable(artifacts.get(tenantId + ':' + artifactId));
    }
}
