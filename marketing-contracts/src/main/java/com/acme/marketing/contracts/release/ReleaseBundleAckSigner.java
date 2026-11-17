package com.acme.marketing.contracts.release;

import com.acme.marketing.platform.crypto.CanonicalMapCodec;
import com.acme.marketing.platform.crypto.Ed25519;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** 对既有单清单ACK再签完整组合上下文，两个协议用途不能互换。 */
public final class ReleaseBundleAckSigner {
    private static final String PURPOSE = "marketing.activity-release-ready/v1";
    private ReleaseBundleAckSigner() { }

    /** ACK密钥仍由各运行端拥有，控制面只能验签。 */
    public static ReleaseBundleAck sign(PrivateKey key, String tenantId, ReleaseBundleAck unsigned) {
        String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(Ed25519.sign(key, canonical(tenantId, unsigned)));
        return new ReleaseBundleAck(unsigned.bundleId(), unsigned.campaignId(), unsigned.version(), unsigned.contentHash(),
                unsigned.memberSignature(), unsigned.runtimeAck(), signature);
    }

    /** 内外签名必须同时通过，不能复用旧ACK或修改其中的容量/身份/时间。 */
    public static boolean verify(PublicKey key, String tenantId, ReleaseBundleAck ack) {
        try {
            return key != null && !ack.signature().isBlank() && RuntimeAckSigner.verify(key, tenantId, ack.runtimeAck())
                    && Ed25519.verify(key, canonical(tenantId, ack), Base64.getUrlDecoder().decode(ack.signature()));
        } catch (IllegalArgumentException malformed) { return false; }
    }

    private static byte[] canonical(String tenantId, ReleaseBundleAck ack) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("purpose", PURPOSE); values.put("tenantId", tenantId);
        values.put("bundleId", ack.bundleId()); values.put("campaignId", ack.campaignId());
        values.put("version", Long.toString(ack.version())); values.put("contentHash", ack.contentHash());
        values.put("memberSignature", ack.memberSignature());
        values.put("runtimeAck", Base64.getEncoder().encodeToString(RuntimeAckSigner.canonical(tenantId, ack.runtimeAck())));
        values.put("runtimeSignature", ack.runtimeAck().signature());
        return CanonicalMapCodec.encode(values);
    }
}
