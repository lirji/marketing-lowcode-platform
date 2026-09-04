package com.acme.marketing.contracts.release;

import com.acme.marketing.platform.crypto.CanonicalMapCodec;
import com.acme.marketing.platform.crypto.Ed25519;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ActivationDirectiveSigner {
    private ActivationDirectiveSigner() { }

    public static ActivationDirective sign(PrivateKey key, ActivationDirective unsigned) {
        String signature = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(Ed25519.sign(key, canonical(unsigned)));
        return new ActivationDirective(unsigned.directiveId(), unsigned.tenantId(), unsigned.manifestId(),
                unsigned.environment(), unsigned.cell(), unsigned.runtime(), unsigned.namespace(),
                unsigned.activationSequence(), unsigned.generation(), unsigned.stableGeneration(),
                unsigned.canaryBasisPoints(), unsigned.manifestSignature(), unsigned.activatedAt(),
                unsigned.expiresAt(), unsigned.activatedBy(), unsigned.signatureKeyId(), signature);
    }

    public static boolean verify(PublicKey key, ActivationDirective directive) {
        try {
            return !directive.signature().isBlank() && Ed25519.verify(key, canonical(directive),
                    Base64.getUrlDecoder().decode(directive.signature()));
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    private static byte[] canonical(ActivationDirective directive) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("directiveId", directive.directiveId());
        values.put("tenantId", directive.tenantId().value());
        values.put("manifestId", directive.manifestId());
        values.put("environment", directive.environment());
        values.put("cell", directive.cell());
        values.put("runtime", directive.runtime());
        values.put("namespace", directive.namespace());
        values.put("activationSequence", Long.toString(directive.activationSequence()));
        values.put("generation", Long.toString(directive.generation()));
        values.put("stableGeneration", Long.toString(directive.stableGeneration()));
        values.put("canaryBasisPoints", Integer.toString(directive.canaryBasisPoints()));
        values.put("manifestSignature", directive.manifestSignature());
        values.put("activatedAt", directive.activatedAt().toString());
        values.put("expiresAt", directive.expiresAt().toString());
        values.put("activatedBy", directive.activatedBy());
        values.put("signatureKeyId", directive.signatureKeyId());
        return CanonicalMapCodec.encode(values);
    }
}
