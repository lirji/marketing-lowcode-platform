package com.acme.marketing.contracts.release;

import java.time.Instant;
import java.util.Set;

public record RuntimeAck(
        String manifestId,
        long generation,
        String runtimeId,
        String cell,
        Status status,
        String buildDigest,
        Set<String> supportedAbis,
        Set<String> warmedArtifactIds,
        long capacity,
        Instant acknowledgedAt,
        String signatureKeyId,
        String signature) {
    public RuntimeAck {
        if (manifestId == null || manifestId.isBlank() || runtimeId == null || runtimeId.isBlank()) {
            throw new IllegalArgumentException("runtime ack identity is required");
        }
        if (generation <= 0 || capacity < 0 || status == null || acknowledgedAt == null
                || cell == null || cell.isBlank() || buildDigest == null || buildDigest.isBlank()
                || signatureKeyId == null || signatureKeyId.isBlank()) {
            throw new IllegalArgumentException("runtime ack is invalid");
        }
        supportedAbis = Set.copyOf(supportedAbis == null ? Set.of() : supportedAbis);
        warmedArtifactIds = Set.copyOf(warmedArtifactIds == null ? Set.of() : warmedArtifactIds);
        if (supportedAbis.stream().anyMatch(value -> value == null || value.isBlank())
                || warmedArtifactIds.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("runtime ack ABI is invalid");
        }
        signature = signature == null ? "" : signature;
    }

    public enum Status {
        DOWNLOADING,
        VERIFIED,
        WARMING,
        READY,
        REJECTED
    }
}
