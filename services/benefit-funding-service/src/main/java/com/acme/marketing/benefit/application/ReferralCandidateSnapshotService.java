package com.acme.marketing.benefit.application;

import com.acme.marketing.benefit.domain.ReferralAwardPreparation.Identity;
import com.acme.marketing.platform.crypto.CanonicalMapCodec;
import com.acme.marketing.platform.crypto.Digests;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/** 事务外保护与恢复原候选；数据库仅保存密文，全身份AAD防止跨来源移植。 */
public final class ReferralCandidateSnapshotService {
    private final ReferralCandidateProtectionPort protection;
    private final ObjectMapper json;
    public ReferralCandidateSnapshotService(ReferralCandidateProtectionPort protection,ObjectMapper json){this.protection=protection;this.json=json;}
    /** 只接受组装器候选，token不在入参或持久内容中。 */
    public Snapshot protect(Identity identity,ReferralAwardIntentAssembler.Candidate candidate) {
        outside();
        try {
            require(candidate!=null && "CANDIDATE_ONLY".equals(candidate.status()) && identity.stableClaimsDigest().equals(candidate.stableClaimsDigest())
                    && identity.payloadHash().equals(candidate.payloadHash()) && identity.sourceRequestId().equals(candidate.intent().sourceRequestId())
                    && identity.sourceSystem().equals(candidate.intent().sourceSystem()) && identity.rewardId().equals(candidate.intent().sourceBusinessNo()));
            String payload=json.writeValueAsString(candidate.intent());require(identity.payloadHash().equals(hash(payload)));
            byte[] aad=aad(identity);var encrypted=protection.encrypt(aad,payload.getBytes(StandardCharsets.UTF_8));require(encrypted!=null);
            // 仅收到可复制的bindingDigest不是认证证明，立即以同AAD回验受保护内容。
            byte[] restored=protection.decrypt(aad,encrypted);require(restored!=null && java.util.Arrays.equals(restored,payload.getBytes(StandardCharsets.UTF_8)));
            return new Snapshot(binding(identity),encrypted);
        } catch(RuntimeException failure){throw rejected();}
    }
    /** 永久恢复仍需受权调用者；重算原payload hash，不信任可重放的AAD摘要字段。 */
    public FrozenPayload restore(Identity identity,Snapshot snapshot) {
        outside();
        try {
            require(snapshot!=null && binding(identity).equals(snapshot.bindingDigest()));
            byte[] restored=protection.decrypt(aad(identity),snapshot.encrypted());require(restored!=null);
            String text=new String(restored,StandardCharsets.UTF_8);
            require(java.util.Arrays.equals(restored,text.getBytes(StandardCharsets.UTF_8)) && identity.payloadHash().equals(hash(text)));
            return new FrozenPayload(text);
        } catch(RuntimeException failure){throw rejected();}
    }
    /** 固定版本规范编码包含完整永久Identity，无字符串分隔符歧义。 */
    public static byte[] aad(Identity i) {
        return CanonicalMapCodec.encode(Map.of("format","referral-prepared-candidate/1","tenant",i.tenantId(),"source",i.sourceSystem(),
                "request",i.sourceRequestId(),"reward",i.rewardId(),"claims",i.stableClaimsDigest(),"payload",i.payloadHash(),"revision",Long.toString(i.qualificationRevision())));
    }
    public static String binding(Identity i){return "sha256:"+Digests.sha256Hex(java.util.Base64.getEncoder().encodeToString(aad(i)));}
    private static String hash(String payload){return "sha256:"+Digests.sha256Hex(payload);}
    private static void outside(){if(TransactionSynchronizationManager.isActualTransactionActive())throw rejected();}
    private static void require(boolean valid){if(!valid)throw rejected();}
    private static IllegalStateException rejected(){return new IllegalStateException("referral candidate protection unavailable");}
    /** bindingDigest仅供存储定位核对，不能代替AEAD认证。 */
    public record Snapshot(String bindingDigest,ReferralCandidateProtectionPort.Encrypted encrypted) {
        public Snapshot{if(bindingDigest==null || !bindingDigest.matches("sha256:[a-f0-9]{64}") || encrypted==null)throw rejected();}
        @Override public String toString(){return "ReferralCandidateSnapshot[redacted]";}
    }
    public record FrozenPayload(String payload){@Override public String toString(){return "FrozenReferralPayload[redacted]";}}
}
