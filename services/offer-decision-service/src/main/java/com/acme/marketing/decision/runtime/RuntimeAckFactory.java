package com.acme.marketing.decision.runtime;

import com.acme.marketing.contracts.release.ArtifactReference;
import com.acme.marketing.contracts.release.ReleaseManifest;
import com.acme.marketing.contracts.release.RuntimeAck;
import com.acme.marketing.contracts.release.RuntimeAckSigner;
import com.acme.marketing.platform.crypto.SigningKeyRing;
import java.time.Clock;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class RuntimeAckFactory {
    private final SigningKeyRing keys;
    private final Clock clock;
    private final String runtimeId;
    private final String buildDigest;
    private final long capacity;

    public RuntimeAckFactory(@Qualifier("runtimeAckSigningKeyRing") SigningKeyRing keys, Clock clock,
            @Value("${marketing.runtime.id:decision-local}") String runtimeId,
            @Value("${marketing.runtime.build-digest:development}") String buildDigest,
            @Value("${marketing.runtime.capacity:1000}") long capacity) {
        if (runtimeId.isBlank() || buildDigest.isBlank() || capacity < 1) {
            throw new IllegalArgumentException("runtime identity and capacity are required");
        }
        this.keys = keys;
        this.clock = clock;
        this.runtimeId = runtimeId;
        this.buildDigest = buildDigest;
        this.capacity = capacity;
    }

    public RuntimeAck ready(ReleaseManifest manifest) {
        RuntimeAck unsigned = new RuntimeAck(manifest.manifestId(), manifest.generation(), runtimeId,
                manifest.cell(), RuntimeAck.Status.READY, buildDigest,
                manifest.artifacts().stream().map(ArtifactReference::abi).collect(Collectors.toUnmodifiableSet()),
                manifest.artifacts().stream().map(ArtifactReference::artifactId)
                        .collect(Collectors.toUnmodifiableSet()),
                capacity, clock.instant(), keys.activeKeyId(), "");
        return RuntimeAckSigner.sign(keys.activePrivateKey(), manifest.tenantId().value(), unsigned);
    }
}
