package com.acme.marketing.referral.domain.quota;

import java.util.Objects;

/**
 * 活动数量配额纯状态边界，不代表资金或权益库存。
 * 返回的桶/预占必须与reward及Outbox在同事务落库；全库reward唯一和个人上限仍由仓储锁保证。
 */
public final class ReferralQuota {
    private ReferralQuota() {}

    /** 桶不足仅说明需冷路径调拨，不能推导整个活动额度耗尽。 */
    public enum Decision { RESERVED, REPLAY, WAIT_QUOTA, RETAIN_TERMINAL }
    public enum ReservationState { RESERVED, CONSUMED, RELEASED }
    /** 输入由可信结果适配器构造；UNKNOWN、202、404及超时均不属于终态证明。 */
    public enum FinalFact { SUCCEEDED, CONFIRMED_NOT_ISSUED, CANCELLED_BEFORE_ISSUE, REVERSED_AFTER_ISSUE }
    public enum ReversalPolicy { RETAIN_CONSUMED, RELEASE_AFTER_CONFIRMED_REVERSAL }

    /** 精确身份参与每次判断，避免跨租户或跨规则使用一个桶的余额。 */
    public record Key(String tenantId,String campaignId,String ruleId,int bucketId) {
        public Key { text(tenantId);text(campaignId);text(ruleId);require(bucketId>=0); }
        @Override public String toString(){return "QuotaKey[redacted]";}
    }
    /** 所有字段非负且精确守恒；溢出即拒绝，不能用溢出后的负值通过额度检查。 */
    public record Bucket(Key key,long allocated,long available,long reserved,long consumed,long epoch,long version) {
        public Bucket {
            Objects.requireNonNull(key);require(allocated>=0 && available>=0 && reserved>=0 && consumed>=0 && epoch>0 && version>0);
            require(allocated==Math.addExact(Math.addExact(available,reserved),consumed));
        }
        public Stamp stamp(){return new Stamp(key,epoch,version);}
    }
    /** 对应锁后实际读取的行版本；不能用调用方提交的期望值冒充当前数据库事实。 */
    public record Stamp(Key key,long epoch,long version) { public Stamp{Objects.requireNonNull(key);require(epoch>0 && version>0);} }
    /** 已释放预占仍永久保留reward身份，禁止同档位重新占用或二次发奖。 */
    public record Reservation(Key bucket,String rewardId,long createdEpoch,ReservationState state,FinalFact terminalFact,String terminalDigest,String successDigest,ReversalPolicy reversalPolicy) {
        public Reservation {
            Objects.requireNonNull(bucket);text(rewardId);require(createdEpoch>0);Objects.requireNonNull(state);Objects.requireNonNull(reversalPolicy);
            require((terminalFact==null)==(terminalDigest==null));if(terminalDigest!=null)digest(terminalDigest);
            require((terminalFact==FinalFact.SUCCEEDED || terminalFact==FinalFact.REVERSED_AFTER_ISSUE)==(successDigest!=null));
            if(successDigest!=null)digest(successDigest);
            if(terminalFact==FinalFact.SUCCEEDED)require(terminalDigest.equals(successDigest));
            require((state==ReservationState.RESERVED)==(terminalFact==null));
            if(state==ReservationState.CONSUMED) require(terminalFact==FinalFact.SUCCEEDED || terminalFact==FinalFact.REVERSED_AFTER_ISSUE);
            if(state==ReservationState.RELEASED) require(terminalFact!=FinalFact.SUCCEEDED);
        }
        @Override public String toString(){return "QuotaReservation["+state+"]";}
    }
    /** 已归一化且验证来源的业务终态；同结果异摘要也必须上交隔离，不能静默当重放。 */
    public record FinalProof(Key bucket,String rewardId,FinalFact fact,String stableDigest) {
        public FinalProof {Objects.requireNonNull(bucket);text(rewardId);Objects.requireNonNull(fact);digest(stableDigest);}
        @Override public String toString(){return "QuotaFinalProof[redacted]";}
    }
    public record Change(Bucket bucket,Reservation reservation,Decision decision) {}
    public record Transfer(Bucket source,Bucket destination) {}

    /**
     * existing由同事务按tenant/reward唯一键读取；null只能表示已完成唯一性检查的未见记录。
     * 纯类无法证明数据库未见，真实接入必须持有participant/个人额度锁并依靠唯一约束处理并发。
     */
    public static Change reserve(Bucket current,Stamp expected,String rewardId,Reservation existing,ReversalPolicy frozenPolicy) {
        check(current,expected);text(rewardId);Objects.requireNonNull(frozenPolicy);
        if(existing!=null){match(current,existing,rewardId);require(existing.reversalPolicy()==frozenPolicy);return new Change(current,existing,
                existing.state()==ReservationState.RESERVED?Decision.REPLAY:Decision.RETAIN_TERMINAL);}
        if(current.available()==0)return new Change(current,null,Decision.WAIT_QUOTA);
        var next=move(current,-1,1,0);
        return new Change(next,new Reservation(current.key(),rewardId,current.epoch(),ReservationState.RESERVED,null,null,null,frozenPolicy),Decision.RESERVED);
    }

    /** 未知结果保留原预占，不允许通过超时或调用方选择release布尔值释放额度。 */
    public static Change retainUnknown(Bucket current,Stamp expected,Reservation existing) {
        check(current,expected);match(current,existing,existing.rewardId());
        return new Change(current,existing,Decision.REPLAY);
    }

    /**
     * 可信终态只移动一次计数；终态冲突拒绝并交上层证据隔离处理，不覆盖原事实。
     * 追回已经消费的名额默认保留，显式冻结政策允许且确认追回时才返还。
     */
    public static Change applyFinal(Bucket current,Stamp expected,Reservation existing,FinalProof proof) {
        check(current,expected);Objects.requireNonNull(proof);Objects.requireNonNull(existing);match(current,existing,proof.rewardId());require(current.key().equals(proof.bucket()));
        FinalFact fact=proof.fact();ReversalPolicy policy=existing.reversalPolicy();
        // 追回不能抹消先前成功证明；合法旧成功重放必须无副作用，异摘要仍冲突。
        if(fact==FinalFact.SUCCEEDED && existing.successDigest()!=null){
            require(existing.successDigest().equals(proof.stableDigest()));return new Change(current,existing,Decision.REPLAY);
        }
        if(existing.terminalFact()==fact){require(proof.stableDigest().equals(existing.terminalDigest()));return new Change(current,existing,Decision.REPLAY);}
        if(existing.state()==ReservationState.RELEASED)throw rejected();
        if(existing.state()==ReservationState.CONSUMED){
            require(existing.terminalFact()==FinalFact.SUCCEEDED && fact==FinalFact.REVERSED_AFTER_ISSUE);
            if(policy==ReversalPolicy.RETAIN_CONSUMED)
                return new Change(current,new Reservation(existing.bucket(),existing.rewardId(),existing.createdEpoch(),ReservationState.CONSUMED,fact,proof.stableDigest(),existing.successDigest(),policy),Decision.RETAIN_TERMINAL);
            return new Change(move(current,1,0,-1),new Reservation(existing.bucket(),existing.rewardId(),existing.createdEpoch(),ReservationState.RELEASED,fact,proof.stableDigest(),existing.successDigest(),policy),Decision.RETAIN_TERMINAL);
        }
        if(fact==FinalFact.SUCCEEDED)
            return new Change(move(current,0,-1,1),new Reservation(existing.bucket(),existing.rewardId(),existing.createdEpoch(),ReservationState.CONSUMED,fact,proof.stableDigest(),proof.stableDigest(),policy),Decision.RETAIN_TERMINAL);
        // 只有先确认成功后才可用“已发放追回”释放consumed；乱序追回交证据层先重建成功事实。
        require(fact!=FinalFact.REVERSED_AFTER_ISSUE);
        return new Change(move(current,1,-1,0),new Reservation(existing.bucket(),existing.rewardId(),existing.createdEpoch(),ReservationState.RELEASED,fact,proof.stableDigest(),existing.successDigest(),policy),Decision.RETAIN_TERMINAL);
    }

    /** 冷路径只调拨空闲量，提升两桶epoch使旧worker失败；必须按桶ID排序锁并同事务保存双行。 */
    public static Transfer transfer(Bucket source,Stamp sourceStamp,Bucket destination,Stamp destinationStamp,long quantity) {
        check(source,sourceStamp);check(destination,destinationStamp);require(quantity>0 && source.available()>=quantity);
        Key a=source.key(),b=destination.key();require(!a.equals(b) && a.tenantId().equals(b.tenantId()) && a.campaignId().equals(b.campaignId()) && a.ruleId().equals(b.ruleId()));
        Bucket from=new Bucket(a,Math.subtractExact(source.allocated(),quantity),Math.subtractExact(source.available(),quantity),source.reserved(),source.consumed(),Math.incrementExact(source.epoch()),Math.incrementExact(source.version()));
        Bucket to=new Bucket(b,Math.addExact(destination.allocated(),quantity),Math.addExact(destination.available(),quantity),destination.reserved(),destination.consumed(),Math.incrementExact(destination.epoch()),Math.incrementExact(destination.version()));
        return new Transfer(from,to);
    }
    private static Bucket move(Bucket b,long available,long reserved,long consumed){return new Bucket(b.key(),b.allocated(),Math.addExact(b.available(),available),Math.addExact(b.reserved(),reserved),Math.addExact(b.consumed(),consumed),b.epoch(),Math.incrementExact(b.version()));}
    private static void check(Bucket b,Stamp s){Objects.requireNonNull(b);require(b.stamp().equals(s));}
    private static void match(Bucket b,Reservation r,String reward){require(r!=null && b.key().equals(r.bucket()) && r.rewardId().equals(reward) && r.createdEpoch()<=b.epoch());}
    private static void digest(String v){require(v!=null && v.matches("sha256:[a-f0-9]{64}"));}
    private static void text(String v){require(v!=null && !v.isBlank() && v.codePointCount(0,v.length())<=256);}
    private static void require(boolean condition){if(!condition)throw rejected();}
    private static IllegalStateException rejected(){return new IllegalStateException("referral quota transition rejected");}
}
