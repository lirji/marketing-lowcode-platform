package com.acme.marketing.referral.domain.authorization;

import com.acme.marketing.contracts.referral.ReferralAwardIdentity;
import java.time.Instant;
import java.util.Objects;

/**
 * 单reward的永久确认/取消纯边界。所有状态必须在reward锁下CAS持久化；本类不认证机器、不验签或发奖。
 * 外部调用先完成，再在锁后提供当前证据水位与原始时间，不能以旧资格投影代替当前退款事实。
 */
public record ReferralRewardAuthorization(Identity identity,long version,Entitlement entitlement,
        Authorization state,Receipt receipt,long cancelRevision) {
    public enum Entitlement { ELIGIBLE, INVALIDATED, EXPIRED }
    public enum Authorization { NONE, CONFIRMED, CANCEL_REQUESTED }
    public ReferralRewardAuthorization {
        Objects.requireNonNull(identity);Objects.requireNonNull(entitlement);Objects.requireNonNull(state);
        require(version>0 && cancelRevision>=0);
        require((state!=Authorization.NONE)==(receipt!=null));
        if(receipt!=null)require(identity.equals(receipt.identity()));
        require((entitlement!=Entitlement.ELIGIBLE)==(cancelRevision>0));
        require((state==Authorization.CANCEL_REQUESTED)==(receipt!=null && entitlement!=Entitlement.ELIGIBLE));
    }
    /** 奖励身份由上层永久唯一账本取得；此构造不能绕过里程碑唯一性或配额预占。 */
    public static ReferralRewardAuthorization eligible(Identity identity){return new ReferralRewardAuthorization(identity,1,Entitlement.ELIGIBLE,Authorization.NONE,null,0);}

    /**
     * 原确认优先回放，旧候选/钥/许可过期不擦除receipt，但返回当前取消状态以阻止新投递。
     * expected身份必须来自已认证机器和永久作用域核对，禁止解析未验签token后直接调用回放。
     */
    public Confirmation confirm(Identity expected,long expectedVersion,NewConfirmation input,Instant now) {
        require(identity.equals(expected));
        if(receipt!=null)return response(true);
        require(version==expectedVersion && entitlement==Entitlement.ELIGIBLE && input!=null && now!=null);
        require(identity.equals(input.identity()));
        input.candidateWindow().check(now);input.permitWindow().check(now);input.riskWindow().check(now);
        require(input.qualificationRevision()==identity.qualificationRevision());
        require(input.currentEvidence().equals(input.projectedEvidence()));
        require(identity.equals(input.currentEvidence().identity()));
        Receipt first=new Receipt(identity,input.confirmationId(),input.authorizationSequence(),now);
        var next=new ReferralRewardAuthorization(identity,Math.incrementExact(version),entitlement,Authorization.CONFIRMED,first,0);
        return next.response(false);
    }
    /**
     * 资格失效不可使同一reward重新分配身份；先确认则待撤销，未确认则阻止首次确认。
     * 重复失效保留第一次不可逆栅栏；不同证据的历史/审计由外层独立账本保存。
     */
    public ReferralRewardAuthorization invalidate(Identity expected,long expectedVersion,Entitlement reason) {
        require(identity.equals(expected) && reason!=null && reason!=Entitlement.ELIGIBLE);
        if(entitlement!=Entitlement.ELIGIBLE)return this;
        require(version==expectedVersion);
        long next=Math.incrementExact(version);
        return new ReferralRewardAuthorization(identity,next,reason,receipt==null?Authorization.NONE:Authorization.CANCEL_REQUESTED,receipt,next);
    }
    private Confirmation response(boolean replay){return new Confirmation(this,receipt,state,version,cancelRevision,replay);}
    @Override public String toString(){return "ReferralRewardAuthorization["+state+"]";}

    /** 摘要绑定完整冻结声明；改变资格修订或受益人不能以同reward重新授权。 */
    public record Identity(String tenantId,String rewardId,String sourceRequestId,String stableClaimsDigest,long qualificationRevision) {
        public Identity {text(tenantId);text(rewardId);digest(stableClaimsDigest);require(qualificationRevision>0);
            require(ReferralAwardIdentity.sourceRequestId(tenantId,rewardId).equals(sourceRequestId));}
        @Override public String toString(){return "RewardAuthorizationIdentity[redacted]";}
    }
    /** 完整证据集合摘要必须包含每个来源、订单、Scope和revision；不能只传两个裸revision比较。 */
    public record EvidenceBasis(Identity identity,String aggregateDigest) {
        public EvidenceBasis{Objects.requireNonNull(identity);digest(aggregateDigest);}
        @Override public String toString(){return "RewardEvidenceBasis[redacted]";}
    }
    /** 时间参数全部显式输入，保留纳秒；本类不给出生产寿命默认值。 */
    public record Window(Instant issuedAt,Instant expiresAt) {
        public Window {require(issuedAt!=null && expiresAt!=null && issuedAt.isBefore(expiresAt));}
        private void check(Instant now){require(!now.isBefore(issuedAt) && now.isBefore(expiresAt));}
    }
    /**
     * 仅供可信应用适配器使用；候选/许可/风险都必须已验证绑定同Identity与当前证据依据。
     * 纯record不是已认证标记，不允许HTTP把客户端字段直接反序列化到此类型。
     */
    public record NewConfirmation(Identity identity,long qualificationRevision,EvidenceBasis currentEvidence,
            EvidenceBasis projectedEvidence,Window candidateWindow,Window permitWindow,Window riskWindow,
            String confirmationId,long authorizationSequence) {
        public NewConfirmation {
            Objects.requireNonNull(identity);Objects.requireNonNull(currentEvidence);Objects.requireNonNull(projectedEvidence);
            Objects.requireNonNull(candidateWindow);Objects.requireNonNull(permitWindow);Objects.requireNonNull(riskWindow);
            require(qualificationRevision>0);text(confirmationId);require(authorizationSequence>0);
        }
    }
    /** 首次确认事实永久不变；撤销状态放在外层响应，不能修改首次签名/摘要伪造未确认。 */
    public record Receipt(Identity identity,String confirmationId,long authorizationSequence,Instant confirmedAt) {
        public Receipt {Objects.requireNonNull(identity);text(confirmationId);require(authorizationSequence>0);Objects.requireNonNull(confirmedAt);}
        @Override public String toString(){return "RewardAuthorizationReceipt[redacted]";}
    }
    /** 调用方必须处理currentState，CANCEL_REQUESTED的历史receipt不能作为新投递许可。 */
    public record Confirmation(ReferralRewardAuthorization next,Receipt receipt,Authorization currentState,
            long currentRevision,long cancelRevision,boolean replay) {}
    private static void text(String v){require(v!=null && !v.isBlank() && v.codePointCount(0,v.length())<=256);
        for(int i=0;i<v.length();i++){char c=v.charAt(i);if(Character.isHighSurrogate(c)){require(++i<v.length() && Character.isLowSurrogate(v.charAt(i)));}else require(!Character.isLowSurrogate(c));}}
    private static void digest(String v){require(v!=null && v.matches("sha256:[a-f0-9]{64}"));}
    private static void require(boolean ok){if(!ok)throw new IllegalStateException("referral reward authorization rejected");}
}
