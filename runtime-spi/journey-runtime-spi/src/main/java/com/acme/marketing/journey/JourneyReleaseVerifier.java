package com.acme.marketing.journey;

import com.acme.marketing.contracts.artifact.ArtifactAttestation;
import com.acme.marketing.contracts.release.ActivationDirective;
import com.acme.marketing.contracts.release.ActivationDirectiveSigner;
import com.acme.marketing.contracts.release.ArtifactReference;
import com.acme.marketing.contracts.release.ReleaseManifest;
import com.acme.marketing.contracts.release.ReleaseManifestSigner;
import com.acme.marketing.platform.crypto.Digests;
import java.security.PublicKey;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;

/** Shared trust policy for journey release stores and stream runtimes. */
public final class JourneyReleaseVerifier {
    private final Map<String, PublicKey> releaseKeys;
    private final Map<String, PublicKey> compilerKeys;
    private final String environment;
    private final String cell;
    private final String namespace;

    public JourneyReleaseVerifier(Map<String, PublicKey> releaseKeys, Map<String, PublicKey> compilerKeys,
            String environment, String cell, String namespace) {
        this.releaseKeys = Map.copyOf(releaseKeys);
        this.compilerKeys = Map.copyOf(compilerKeys);
        this.environment = required(environment, "environment");
        this.cell = required(cell, "cell");
        this.namespace = required(namespace, "namespace");
    }

    public VerifiedArtifact verifyInstallation(String tenantId, String releaseKeyId, ReleaseManifest manifest,
            byte[] artifactPayload, Instant now) {
        PublicKey releaseKey = releaseKeys.get(releaseKeyId);
        if (releaseKey == null || !new ReleaseManifestSigner().verify(manifest, releaseKey)) {
            throw new IllegalArgumentException("journey release manifest signature is not trusted");
        }
        if (!tenantId.equals(manifest.tenantId().value()) || !slot(manifest.environment(), manifest.cell(),
                manifest.runtime(), manifest.namespace()) || !manifest.expiresAt().isAfter(now)) {
            throw new IllegalArgumentException("journey release tenant, slot or lifetime is invalid");
        }
        if (manifest.artifacts().size() != 1 || artifactPayload == null || artifactPayload.length == 0
                || artifactPayload.length > 1_048_576) {
            throw new IllegalArgumentException("journey manifest artifact closure is invalid");
        }
        ArtifactReference artifact = manifest.artifacts().getFirst();
        PublicKey compilerKey = compilerKeys.get(artifact.signatureKeyId());
        if (!"JOURNEY_PLAN".equals(artifact.type()) || !"marketing-journey-plan/1".equals(artifact.abi())
                || compilerKey == null || !ArtifactAttestation.verify(compilerKey, tenantId, artifact)
                || !artifact.checksum().equals("sha256:" + Digests.sha256Hex(artifactPayload))) {
            throw new IllegalArgumentException("journey artifact attestation or checksum is invalid");
        }
        return new VerifiedArtifact(artifact, artifactPayload);
    }

    public void verifyActivation(String tenantId, ActivationDirective activation,
            ReleaseManifest installedManifest, Instant now) {
        PublicKey releaseKey = releaseKeys.get(activation.signatureKeyId());
        if (releaseKey == null || !ActivationDirectiveSigner.verify(releaseKey, activation)) {
            throw new IllegalArgumentException("journey activation signature is not trusted");
        }
        if (!tenantId.equals(activation.tenantId().value()) || !slot(activation.environment(), activation.cell(),
                activation.runtime(), activation.namespace()) || activation.activatedAt().isAfter(now.plusSeconds(60))
                || !activation.expiresAt().isAfter(now)) {
            throw new IllegalArgumentException("journey activation tenant, slot or lifetime is invalid");
        }
        if (!activation.manifestId().equals(installedManifest.manifestId())
                || !activation.manifestSignature().equals(installedManifest.signature())
                || activation.generation() != installedManifest.generation()
                || activation.canaryBasisPoints() != 0
                || activation.stableGeneration() != activation.generation()
                || activation.expiresAt().isAfter(installedManifest.expiresAt())) {
            throw new IllegalArgumentException("journey activation does not bind the stable installed manifest");
        }
    }

    private boolean slot(String candidateEnvironment, String candidateCell, String runtime,
            String candidateNamespace) {
        return environment.equals(candidateEnvironment) && cell.equals(candidateCell) && "journey".equals(runtime)
                && namespace.equals(candidateNamespace);
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    public record VerifiedArtifact(ArtifactReference reference, byte[] payload) {
        public VerifiedArtifact {
            payload = payload.clone();
        }
        @Override public byte[] payload() { return payload.clone(); }
        @Override public boolean equals(Object other) {
            return other instanceof VerifiedArtifact that && reference.equals(that.reference)
                    && Arrays.equals(payload, that.payload);
        }
        @Override public int hashCode() { return 31 * reference.hashCode() + Arrays.hashCode(payload); }
    }
}
