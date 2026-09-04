package com.acme.marketing.contracts.artifact;

import com.acme.marketing.contracts.release.ArtifactReference;
import com.acme.marketing.platform.crypto.CanonicalMapCodec;
import com.acme.marketing.platform.crypto.Ed25519;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** Cryptographic attestation binding compiled content to its tenant and approved source definition. */
public final class ArtifactAttestation {
    private ArtifactAttestation() { }

    public static String sign(PrivateKey key, String tenantId, String artifactId, String definitionId,
            long definitionVersion, String type, String abi, String checksum, String sourceDigest) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(Ed25519.sign(key,
                canonical(tenantId, artifactId, definitionId, definitionVersion, type, abi, checksum, sourceDigest)));
    }

    public static boolean verify(PublicKey key, String tenantId, ArtifactReference reference) {
        try {
            return Ed25519.verify(key, canonical(tenantId, reference.artifactId(), reference.definitionId(),
                    reference.definitionVersion(), reference.type(), reference.abi(), reference.checksum(),
                    reference.sourceDigest()),
                    Base64.getUrlDecoder().decode(reference.signature()));
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    private static byte[] canonical(String tenantId, String artifactId, String definitionId,
            long definitionVersion, String type, String abi, String checksum, String sourceDigest) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("tenantId", tenantId);
        values.put("artifactId", artifactId);
        values.put("definitionId", definitionId);
        values.put("definitionVersion", Long.toString(definitionVersion));
        values.put("type", type);
        values.put("abi", abi);
        values.put("checksum", checksum);
        values.put("sourceDigest", sourceDigest);
        return CanonicalMapCodec.encode(values);
    }
}
