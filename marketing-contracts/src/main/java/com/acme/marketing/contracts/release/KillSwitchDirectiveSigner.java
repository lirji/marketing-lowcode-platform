package com.acme.marketing.contracts.release;

import com.acme.marketing.platform.crypto.CanonicalMapCodec;
import com.acme.marketing.platform.crypto.Ed25519;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

public final class KillSwitchDirectiveSigner {
    private KillSwitchDirectiveSigner() { }

    public static KillSwitchDirective sign(PrivateKey key, KillSwitchDirective unsigned) {
        String signature = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(Ed25519.sign(key, canonical(unsigned)));
        return new KillSwitchDirective(unsigned.directiveId(), unsigned.tenantId(), unsigned.namespace(),
                unsigned.switchSequence(), unsigned.enabled(), unsigned.reason(), unsigned.activatedAt(),
                unsigned.activatedBy(), unsigned.signatureKeyId(), signature);
    }

    public static boolean verify(PublicKey key, KillSwitchDirective directive) {
        try {
            return !directive.signature().isBlank() && Ed25519.verify(key, canonical(directive),
                    Base64.getUrlDecoder().decode(directive.signature()));
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    private static byte[] canonical(KillSwitchDirective directive) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("directiveId", directive.directiveId());
        values.put("tenantId", directive.tenantId().value());
        values.put("namespace", directive.namespace());
        values.put("switchSequence", Long.toString(directive.switchSequence()));
        values.put("enabled", Boolean.toString(directive.enabled()));
        values.put("reason", directive.reason());
        values.put("activatedAt", directive.activatedAt().toString());
        values.put("activatedBy", directive.activatedBy());
        values.put("signatureKeyId", directive.signatureKeyId());
        return CanonicalMapCodec.encode(values);
    }
}
