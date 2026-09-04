package com.acme.marketing.contracts.release;

import com.acme.marketing.platform.crypto.CanonicalMapCodec;
import com.acme.marketing.platform.crypto.Ed25519;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

/** Signs runtime warm-up acknowledgements so release quorum cannot be supplied by an API caller. */
public final class RuntimeAckSigner {
    private RuntimeAckSigner() { }

    public static RuntimeAck sign(PrivateKey key, String tenantId, RuntimeAck unsigned) {
        String signature = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(Ed25519.sign(key, canonical(tenantId, unsigned)));
        return new RuntimeAck(unsigned.manifestId(), unsigned.generation(), unsigned.runtimeId(), unsigned.cell(),
                unsigned.status(), unsigned.buildDigest(), unsigned.supportedAbis(), unsigned.warmedArtifactIds(),
                unsigned.capacity(), unsigned.acknowledgedAt(), unsigned.signatureKeyId(), signature);
    }

    public static boolean verify(PublicKey key, String tenantId, RuntimeAck ack) {
        try {
            return !ack.signature().isBlank() && Ed25519.verify(key, canonical(tenantId, ack),
                    Base64.getUrlDecoder().decode(ack.signature()));
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    private static byte[] canonical(String tenantId, RuntimeAck ack) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("tenantId", tenantId);
        values.put("manifestId", ack.manifestId());
        values.put("generation", Long.toString(ack.generation()));
        values.put("runtimeId", ack.runtimeId());
        values.put("cell", ack.cell());
        values.put("status", ack.status().name());
        values.put("buildDigest", ack.buildDigest());
        values.put("supportedAbis", String.join(",", new TreeSet<>(ack.supportedAbis())));
        values.put("warmedArtifactIds", String.join(",", new TreeSet<>(ack.warmedArtifactIds())));
        values.put("capacity", Long.toString(ack.capacity()));
        values.put("acknowledgedAt", ack.acknowledgedAt().toString());
        values.put("signatureKeyId", ack.signatureKeyId());
        return CanonicalMapCodec.encode(values);
    }
}
