package com.acme.marketing.contracts.release;

import com.acme.marketing.platform.crypto.CanonicalMapCodec;
import com.acme.marketing.platform.crypto.Ed25519;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ReleaseManifestSigner {
    public ReleaseManifest sign(ReleaseManifest manifest, PrivateKey privateKey) {
        String signature = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(Ed25519.sign(privateKey, canonical(manifest)));
        return copy(manifest, signature);
    }

    public boolean verify(ReleaseManifest manifest, PublicKey publicKey) {
        try {
            return Ed25519.verify(publicKey, canonical(manifest),
                    Base64.getUrlDecoder().decode(manifest.signature()));
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    private static byte[] canonical(ReleaseManifest manifest) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("manifestId", manifest.manifestId());
        values.put("tenantId", manifest.tenantId().value());
        values.put("environment", manifest.environment());
        values.put("cell", manifest.cell());
        values.put("runtime", manifest.runtime());
        values.put("namespace", manifest.namespace());
        values.put("generation", Long.toString(manifest.generation()));
        values.put("stableGeneration", Long.toString(manifest.stableGeneration()));
        values.put("canaryBasisPoints", Integer.toString(manifest.canaryBasisPoints()));
        values.put("activationAt", manifest.activationAt().toString());
        values.put("expiresAt", manifest.expiresAt().toString());
        values.put("createdBy", manifest.createdBy());
        values.put("createdAt", manifest.createdAt().toString());
        indexed(values, "canaryGeneration", manifest.canaryGenerations().stream().map(String::valueOf).toList());
        indexed(values, "retainedGeneration", manifest.retainedGenerations().stream().map(String::valueOf).toList());
        indexed(values, "approvalCase", manifest.approvalCaseIds());
        List<ArtifactReference> artifacts = new ArrayList<>(manifest.artifacts());
        artifacts.sort(java.util.Comparator.comparing(ArtifactReference::artifactId));
        for (int index = 0; index < artifacts.size(); index++) {
            ArtifactReference artifact = artifacts.get(index);
            String prefix = "artifact." + index + '.';
            values.put(prefix + "id", artifact.artifactId());
            values.put(prefix + "type", artifact.type());
            values.put(prefix + "uri", artifact.uri());
            values.put(prefix + "checksum", artifact.checksum());
            values.put(prefix + "sourceDigest", artifact.sourceDigest());
            values.put(prefix + "signatureKeyId", artifact.signatureKeyId());
            values.put(prefix + "signature", artifact.signature());
            values.put(prefix + "abi", artifact.abi());
            values.put(prefix + "definitionId", artifact.definitionId());
            values.put(prefix + "definitionVersion", Long.toString(artifact.definitionVersion()));
        }
        manifest.schemaVersions().forEach((key, value) -> values.put("schema." + key, value));
        return CanonicalMapCodec.encode(values);
    }

    private static void indexed(Map<String, String> values, String prefix, List<String> items) {
        for (int index = 0; index < items.size(); index++) {
            values.put(prefix + '.' + index, items.get(index));
        }
    }

    private static ReleaseManifest copy(ReleaseManifest manifest, String signature) {
        return new ReleaseManifest(manifest.manifestId(), manifest.tenantId(), manifest.environment(), manifest.cell(),
                manifest.runtime(), manifest.namespace(), manifest.generation(), manifest.stableGeneration(),
                manifest.canaryGenerations(), manifest.retainedGenerations(), manifest.artifacts(),
                manifest.schemaVersions(), manifest.canaryBasisPoints(), manifest.activationAt(), manifest.expiresAt(),
                manifest.createdBy(), manifest.approvalCaseIds(), manifest.createdAt(), signature);
    }
}
