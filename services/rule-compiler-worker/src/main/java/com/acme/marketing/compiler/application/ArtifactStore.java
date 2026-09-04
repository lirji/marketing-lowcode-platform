package com.acme.marketing.compiler.application;

import com.acme.marketing.contracts.artifact.ArtifactBundle;
import java.util.Optional;

public interface ArtifactStore {
    ArtifactBundle putIfAbsent(ArtifactBundle artifact);

    Optional<ArtifactBundle> find(String tenantId, String artifactId);
}
