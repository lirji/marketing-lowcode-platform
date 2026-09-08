package com.acme.marketing.referral.application;
import com.acme.marketing.referral.domain.ReferralParticipant;
import com.acme.marketing.referral.application.ReferralInviteProtectionPort.*;
import java.time.Instant;
/** 邀请token永久身份及短期密文回执仓储，无删除/自动清理方法。 */
public interface ReferralInviteRepository {
    /** 非锁读取用于事务外选择是否需要加密候选，不代替最终命令锁。 */
    Replay findReplay(String tenant,String subject,String key);
    /** 事务内预留并锁定命令，已有hash永久不可替换。 */
    Replay lockReplay(String tenant,String subject,String key,String requestHash,Instant now);
    /** 读取参与者及其所有权；lock=true仅供当前本地事务最终裁决。 */
    Owned participant(String tenant,String participantId,boolean lock);
    /** 原子保存token身份、命令密文及去token审计，任一步失败回滚。 */
    void save(Replay replay,String participantId,String actorId,String traceId,Instant now);
    /** 按租户及token摘要查询；不存在不可跨租户探测。 */
    Token token(String tenant,String tokenHash);
    /** 主体HMAC仅在内部比对，不进入发行/解析响应。 */
    record Owned(ReferralParticipant participant,String subjectKey,long keyVersion) {}
    /** 完成前token字段为空，完成后为不可改写的原发行回执。 */
    record Replay(String tenantId,String subjectKey,String idempotencyKey,String requestHash,String tokenId,String tokenHash,
            Instant tokenExpiresAt,Instant replayUntil,byte[] responseCipher,String encryptionKeyId) {
        /** 副本防止候选/数据库密文在调用栈中被改写。 */
        public Replay { if(responseCipher!=null) responseCipher=responseCipher.clone(); }
        /** 密文只读副本。 */
        @Override public byte[] responseCipher() { return responseCipher==null?null:responseCipher.clone(); }
        /** 重建不可变AAD，不把明文token写入数据库。 */
        public Context context() { return new Context(tenantId,subjectKey,idempotencyKey,requestHash,tokenId,tokenHash,tokenExpiresAt,replayUntil); }
        /** 诊断只显示是否已完成，不显示个人HMAC/密文。 */
        @Override public String toString() { return "InviteReplay[completed="+(tokenId!=null)+"]"; }
    }
    /** 永久token身份，即使过期/撤销也保留；真正使用时仍须重新校验。 */
    record Token(String tokenId,String participantId,String tokenHash,Instant expiresAt,Instant revokedAt) {}
}
