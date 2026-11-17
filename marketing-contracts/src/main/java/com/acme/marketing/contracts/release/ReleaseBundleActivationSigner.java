package com.acme.marketing.contracts.release;

import com.acme.marketing.platform.crypto.CanonicalMapCodec;
import com.acme.marketing.platform.crypto.Ed25519;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** 激活用途与准备证明/READY ACK隔离，完整覆盖内层组合及其签名。 */
public final class ReleaseBundleActivationSigner {
    private static final String PURPOSE = "marketing.activity-release-activation/v1";
    private ReleaseBundleActivationSigner() { }

    /** 仅供已完成发布门禁的控制命令使用；签名函数本身不检查审批或运行就绪。 */
    public static ReleaseBundleActivationDirective sign(PrivateKey key, ReleaseBundleActivationDirective unsigned) {
        String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(Ed25519.sign(key, canonical(unsigned)));
        return new ReleaseBundleActivationDirective(unsigned.directiveId(), unsigned.bundle(), unsigned.activationSequence(),
                unsigned.action(), unsigned.organizationId(), unsigned.shopId(), unsigned.authorizedAt(),
                unsigned.authorizedBy(), unsigned.signatureKeyId(), signature);
    }

    /** 这里只核验激活签名；接收边界必须使用PinnedReleaseBundleActivationVerifier同时验证内层来源。 */
    public static boolean verify(PublicKey key, ReleaseBundleActivationDirective directive) {
        try {
            return key != null && !directive.signature().isBlank() && Ed25519.verify(key, canonical(directive),
                    Base64.getUrlDecoder().decode(directive.signature()));
        } catch (IllegalArgumentException malformed) { return false; }
    }

    private static byte[] canonical(ReleaseBundleActivationDirective directive) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("purpose", PURPOSE);
        values.put("directiveId", directive.directiveId());
        values.put("bundle.content", Base64.getEncoder().encodeToString(ReleaseBundleManifestSigner.canonical(directive.bundle())));
        values.put("bundle.signature", directive.bundle().signature());
        values.put("activationSequence", Long.toString(directive.activationSequence()));
        values.put("action", directive.action());
        values.put("organizationId", directive.organizationId()); values.put("shopId", directive.shopId());
        values.put("authorizedAt", directive.authorizedAt().toString()); values.put("authorizedBy", directive.authorizedBy());
        values.put("signatureKeyId", directive.signatureKeyId());
        return CanonicalMapCodec.encode(values);
    }
}
