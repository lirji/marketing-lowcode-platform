package com.acme.marketing.decision.application;

import com.acme.marketing.contracts.release.ReleaseBundleParticipation;
import com.acme.marketing.contracts.release.ReleaseBundleParticipationSigner;
import com.acme.marketing.contracts.release.ReleaseBundleProtocolError;
import com.acme.marketing.platform.crypto.Ed25519KeyPairCodec;
import com.acme.marketing.platform.crypto.SigningKeyRing;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 专用参与签发身份；缺省无私钥，不为正式事实生成进程临时密钥。 */
@Component
public final class BundleParticipationSigningService {
    private final SigningKeyRing keys;
    /** 部分配置在启动时拒绝，完全未配置则仅关闭新准入签发。 */
    public BundleParticipationSigningService(@Value("${marketing.participation.signing-key-id:}") String keyId,
            @Value("${marketing.participation.private-key-base64:}") String privateKey,
            @Value("${marketing.participation.signing-public-key-base64:}") String publicKey) {
        if (keyId.isBlank() && privateKey.isBlank() && publicKey.isBlank()) { keys = null; return; }
        if (!keyId.matches("[A-Za-z0-9_.:@-]{1,64}") || privateKey.isBlank() || publicKey.isBlank())
            throw new IllegalStateException(ReleaseBundleProtocolError.PARTICIPATION_SIGNING_UNAVAILABLE.message());
        keys = new SigningKeyRing(keyId, Ed25519KeyPairCodec.decode(privateKey, publicKey));
    }
    /** 不接受调用方挑选签名角色或自动复用OfferToken钥匙。 */
    public String keyId() {
        if (keys == null) throw ReleaseBundleProtocolError.PARTICIPATION_SIGNING_UNAVAILABLE.conflict();
        return keys.activeKeyId();
    }
    /** 签名只在事务内保存完整事实前生成，不把待提交对象提前返回。 */
    public ReleaseBundleParticipation sign(ReleaseBundleParticipation value) {
        if (!keyId().equals(value.signatureKeyId())) throw ReleaseBundleProtocolError.PARTICIPATION_SIGNING_UNAVAILABLE.conflict();
        return ReleaseBundleParticipationSigner.sign(keys.activePrivateKey(), value);
    }
}
