package com.acme.marketing.benefit.domain;

import com.acme.marketing.contracts.referral.ReferralAwardIdentity;
import java.time.Instant;
import java.util.Objects;

/**
 * 永久准备/确认恢复的纯领域边界；返回的新状态必须由未来仓储CAS持久化后才可执行外部动作。
 * 不包含删除/abandon入口，不负责验签、远端调用、数据库原子性或权益履约成功。
 */
public record ReferralAwardPreparation(Identity identity,Phase phase,Lease lease,long stateVersion,
        Receipt receipt,String acceptedIntentId,Rejection rejection,boolean quarantined) {
    /** 重建持久状态仍校验结构，防止丢失确认回执后错误降级重试。 */
    public ReferralAwardPreparation {
        Objects.requireNonNull(identity);Objects.requireNonNull(phase);Objects.requireNonNull(lease);
        require(stateVersion>0);
        require((phase==Phase.CONFIRMED || phase==Phase.ACCEPTED)==(receipt!=null));
        require((phase==Phase.ACCEPTED)==(acceptedIntentId!=null));
        require((phase==Phase.REJECTED)==(rejection!=null));
        if(rejection!=null)require(identity.equals(rejection.identity()));
        if(receipt!=null)require(identity.equals(receipt.identity()));
        if(acceptedIntentId!=null)text(acceptedIntentId);
    }

    /** 初次准备必须由唯一来源键插入；先提交此记录，暂不可产生任何可投递Outbox。 */
    public static ReferralAwardPreparation prepare(Identity identity,String owner,Instant now,Instant leaseUntil) {
        return new ReferralAwardPreparation(identity,Phase.PREPARED,new Lease(owner,1,now,leaseUntil),1,null,null,null,false);
    }

    /** 租约失效只能接管同一永久记录，保留未知/已确认状态和原确认身份。 */
    public ReferralAwardPreparation takeOver(Identity expected,String owner,Instant now,Instant until) {
        match(expected);require(!quarantined && phase!=Phase.ACCEPTED && phase!=Phase.REJECTED);
        require(!now.isBefore(lease.expiresAt()));
        return new ReferralAwardPreparation(identity,phase,new Lease(owner,Math.addExact(lease.fence(),1),now,until),nextVersion(),receipt,acceptedIntentId,rejection,false);
    }

    /**
     * 只有当前有效授权和风险ALLOW才可开始首次确认；此状态提交之后才能在事务外调用confirm。
     * 远端幂等键只来自identity，不能使用lease fence或重试次数重新生成。
     */
    public ReferralAwardPreparation beginConfirmation(Identity expected,Owner owner,Admission admission,Instant now) {
        own(expected,owner,now);require(phase==Phase.PREPARED);admit(admission,now);
        require(!now.isBefore(admission.authorizationIssuedAt()) && now.isBefore(admission.authorizationExpiresAt()));
        return next(Phase.CONFIRMING,null,null,false);
    }

    /** 超时仅表示未知；不可删除准备记录、释放身份或退回PREPARED生成第二次授权。 */
    public ReferralAwardPreparation confirmationUnknown(Identity expected,Owner owner,Instant now) {
        own(expected,owner,now);require(phase==Phase.CONFIRMING || phase==Phase.CONFIRM_UNKNOWN);
        return phase==Phase.CONFIRM_UNKNOWN?this:next(Phase.CONFIRM_UNKNOWN,null,null,false);
    }

    /**
     * 输入必须来自受信confirm适配器或其永久查询；原token过期不妨碍恢复同一永久确认。
     * 确认ID或其他稳定内容冲突后返回粘性隔离态，后续不得使用旧receipt继续本地受理。
     */
    public ReferralAwardPreparation recordConfirmation(Identity expected,Owner owner,Receipt confirmed,Instant now) {
        own(expected,owner,now);Objects.requireNonNull(confirmed);
        require(phase==Phase.CONFIRMING || phase==Phase.CONFIRM_UNKNOWN || phase==Phase.CONFIRMED);
        if(!identity.equals(confirmed.identity()) || confirmed.confirmedAt().isAfter(now))
            return next(phase,receipt,acceptedIntentId,true);
        if(receipt!=null) return receipt.equals(confirmed)?this:next(phase,receipt,acceptedIntentId,true);
        return next(Phase.CONFIRMED,confirmed,null,false);
    }

    /** 只有受信远端明确裁决未确认时才可终止；确认之后取消属于补偿流程，禁止这里抹消receipt。 */
    public ReferralAwardPreparation rejectConfirmation(Identity expected,Owner owner,Rejection rejection,Instant now) {
        own(expected,owner,now);require(phase==Phase.CONFIRMING || phase==Phase.CONFIRM_UNKNOWN);
        require(rejection!=null && identity.equals(rejection.identity()) && !rejection.decidedAt().isAfter(now));
        return new ReferralAwardPreparation(identity,Phase.REJECTED,lease,nextVersion(),null,null,rejection,false);
    }

    /**
     * 此转换仅计划本地耐久受理，调用侧必须同事务保存receipt/intent/Outbox/expected-fact。
     * 永久receipt取代旧短token用于恢复，但CENTER和当前风险许可仍需重新获得。
     */
    public ReferralAwardPreparation acceptLocally(Identity expected,Owner owner,String intentId,Admission admission,Instant now) {
        own(expected,owner,now);require(phase==Phase.CONFIRMED);admit(admission,now);text(intentId);
        return next(Phase.ACCEPTED,receipt,intentId,false);
    }

    /** 原本地成功永久回放不依赖旧租约/短期token，但调用侧仍必须认证机器和完整永久身份。 */
    public String replay(Identity expected) {
        match(expected);require(!quarantined && phase==Phase.ACCEPTED);return acceptedIntentId;
    }

    /** 恢复动作不表示网络已执行；CONFIRMING崩溃与UNKNOWN均用同永久确认身份查询/重放。 */
    public Recovery recovery() {
        if(quarantined)return Recovery.QUARANTINE;
        return switch(phase) {
            case PREPARED->Recovery.EVALUATE_CURRENT_ADMISSION;
            case CONFIRMING,CONFIRM_UNKNOWN->Recovery.RECOVER_SAME_CONFIRMATION;
            case CONFIRMED->Recovery.COMMIT_LOCAL_WITH_RECEIPT;
            case ACCEPTED->Recovery.REPLAY_LOCAL_ACCEPTANCE;
            case REJECTED->Recovery.RETAIN_REJECTION;
        };
    }

    private void own(Identity expected,Owner owner,Instant now) {
        match(expected);require(!quarantined && owner!=null && owner.owner().equals(lease.owner()) && owner.fence()==lease.fence());
        require(now!=null && !now.isBefore(lease.acquiredAt()) && now.isBefore(lease.expiresAt()));
    }
    private void match(Identity expected){require(identity.equals(expected));}
    private void admit(Admission admission,Instant now) {
        require(admission!=null && identity.equals(admission.identity()) && admission.centerMode()
                && !now.isBefore(admission.riskIssuedAt()) && now.isBefore(admission.riskExpiresAt()));
    }
    private ReferralAwardPreparation next(Phase phase,Receipt receipt,String intent,boolean quarantined) {
        return new ReferralAwardPreparation(identity,phase,lease,nextVersion(),receipt,intent,rejection,quarantined);
    }
    private long nextVersion(){return Math.addExact(stateVersion,1);}
    private static void require(boolean valid){if(!valid)throw new IllegalStateException("referral preparation transition rejected");}
    private static void text(String value){require(value!=null && !value.isBlank() && value.length()<=256);}
    private static void digest(String value){require(value!=null && value.matches("sha256:[a-f0-9]{64}"));}
    @Override public String toString(){return "ReferralAwardPreparation["+phase+",quarantined="+quarantined+"]";}

    public enum Phase { PREPARED,CONFIRMING,CONFIRM_UNKNOWN,CONFIRMED,ACCEPTED,REJECTED }
    public enum Recovery { EVALUATE_CURRENT_ADMISSION,RECOVER_SAME_CONFIRMATION,COMMIT_LOCAL_WITH_RECEIPT,REPLAY_LOCAL_ACCEPTANCE,RETAIN_REJECTION,QUARANTINE }

    /** claims摘要包含完整组织/店铺/主体/角色/规则/制品；版本变化是冲突，不能重新解释同reward。 */
    public record Identity(String tenantId,String sourceSystem,String sourceRequestId,String rewardId,
            String stableClaimsDigest,String payloadHash,long qualificationRevision) {
        public Identity {
            text(tenantId);text(rewardId);digest(stableClaimsDigest);digest(payloadHash);require(qualificationRevision>0);
            require("marketing-referral".equals(sourceSystem));
            require(ReferralAwardIdentity.sourceRequestId(tenantId,rewardId).equals(sourceRequestId));
        }
        @Override public String toString(){return "ReferralPreparationIdentity[redacted]";}
    }
    /** fence是本地持久CAS所有权，不能用来改变远端幂等身份。 */
    public record Owner(String owner,long fence){public Owner{text(owner);require(fence>0);}}
    /** 所有寿命由调用侧显式输入，不采用生产默认值；时间一律保持原始精度。 */
    public record Lease(String owner,long fence,Instant acquiredAt,Instant expiresAt) {
        public Lease{text(owner);require(fence>0 && acquiredAt!=null && expiresAt!=null && acquiredAt.isBefore(expiresAt));}
    }
    /** 受信适配器验证后的永久确认结构；本record本身不完成密码学校验，也没有任意verified布尔值。 */
    public record Receipt(Identity identity,String confirmationId,Instant confirmedAt) {
        public Receipt{Objects.requireNonNull(identity);text(confirmationId);Objects.requireNonNull(confirmedAt);}
        @Override public String toString(){return "ReferralConfirmationReceipt[redacted]";}
    }
    /** 仅接受明确未确认终态；超时/未知不得创建此结果。 */
    public record Rejection(Identity identity,String decisionId,Instant decidedAt) {
        public Rejection{Objects.requireNonNull(identity);text(decisionId);Objects.requireNonNull(decidedAt);}
    }
    /** 当前风险ALLOW证明和短授权时窗由可信应用层提供；不允许HTTP直接反序列化为授权。 */
    public record Admission(Identity identity,boolean centerMode,String riskDecisionId,Instant riskIssuedAt,
            Instant riskExpiresAt,Instant authorizationIssuedAt,Instant authorizationExpiresAt) {
        public Admission {
            Objects.requireNonNull(identity);text(riskDecisionId);
            require(riskIssuedAt!=null && riskExpiresAt!=null && riskIssuedAt.isBefore(riskExpiresAt));
            require(authorizationIssuedAt!=null && authorizationExpiresAt!=null && authorizationIssuedAt.isBefore(authorizationExpiresAt));
        }
    }
}
