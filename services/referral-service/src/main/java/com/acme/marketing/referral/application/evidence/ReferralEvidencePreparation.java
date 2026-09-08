package com.acme.marketing.referral.application.evidence;

import com.acme.marketing.referral.ReferralOrderEvidence.*;
import com.acme.marketing.referral.ReferralOrderEvidenceMerger;
import com.acme.marketing.referral.ReferralOrderEvidenceMerger.*;
import java.time.*;
import java.util.Objects;

/**
 * 事务外解密后的确定性准备边界；输出仍须加密并在短事务锁后核对版本才能持久化。
 * 不访问数据库、时钟或KMS，不把APPLIED解释成活动资格，更不提供发奖授权。
 */
public final class ReferralEvidencePreparation {
    private ReferralEvidencePreparation() { }

    /** 订单业务身份不包含主体/组织/店铺，防止Scope漂移被当成第二份合法订单。 */
    public record OrderKey(String tenantId,String sourceSystem,String orderId) {
        /** 来源命名空间必须由受信适配器明确提供，不能从客户端路由猜测。 */
        public OrderKey { text(tenantId);text(sourceSystem);text(orderId); }
        /** 从内存规范Scope取得非主体业务身份。 */
        public static OrderKey of(Scope scope) { return new OrderKey(scope.tenantId(),scope.sourceSystem(),scope.orderId()); }
        /** 诊断只保留类型，不输出订单与租户业务信息。 */
        @Override public String toString() { return "OrderEvidenceKey[redacted]"; }
    }

    /** Port已验证的单次输入；只能由可信接入实现创建，未来公开入口不能绑定此Java类型。 */
    public record VerifiedInput(Observation observation,String subjectKey,long keyVersion,Instant issuedAt,Instant expiresAt) {
        /** 这里只校验有效期结构；来源寿命由prepare的显式受信配置约束，无生产默认值。 */
        public VerifiedInput {
            Objects.requireNonNull(observation);digest(subjectKey);Objects.requireNonNull(issuedAt);Objects.requireNonNull(expiresAt);
            if(!observation.sourceVerified() || keyVersion<=0 || !expiresAt.isAfter(issuedAt)) throw invalid();
        }
        /** 不输出规范主体、HMAC或完整累计证据。 */
        @Override public String toString() { return "VerifiedOrderEvidence[redacted]"; }
    }

    /** 历史存在性证明必须与事务外实际查到的完整Snapshot配对，Unavailable不能伪装NotSeen。 */
    public record HistoryRead(long revision,HistoryLookup lookup,String businessDigest) {
        /** PRESENT摘要仅对受信HMAC替代主体后的稳定业务内容计算，不能含原canonicalSubject。 */
        public HistoryRead {
            Objects.requireNonNull(lookup);if(revision<=0)throw invalid();
            if(lookup instanceof Seen seen) { digest(businessDigest);if(seen.snapshot().revision()!=revision)throw invalid(); }
            else if(businessDigest!=null) throw invalid();
        }
        /** 锁内只比较存在性与已存稳定摘要，不触发解密。 */
        public HistoryStamp stamp() { return new HistoryStamp(revision,lookup instanceof Seen?Presence.PRESENT:lookup instanceof NotSeen?Presence.ABSENT:Presence.UNAVAILABLE,businessDigest); }
        /** 不输出内存解密历史。 */
        @Override public String toString() { return "OrderEvidenceHistory[redacted]"; }
    }
    /** 历史查重的三态，未读取不等于未存在。 */
    public enum Presence { ABSENT,PRESENT,UNAVAILABLE }
    /** 无需解密即可在锁内重新查询的历史CAS证明。 */
    public record HistoryStamp(long revision,Presence presence,String businessDigest) {
        /** 摘要与存在性严格配对，不接受缺少完整内容证明的PRESENT。 */
        public HistoryStamp { if(revision<=0)throw invalid();Objects.requireNonNull(presence);if(presence==Presence.PRESENT)digest(businessDigest);else if(businessDigest!=null)throw invalid(); }
    }
    /** 事务外读取并解密的聚合；rowVersion=0仅表示尚不存在，不能承载已提交空状态。 */
    public record CurrentRead(long rowVersion,String subjectKey,long keyVersion,State state) {
        /** 正式已有聚合必须具备受信索引键和完整State，不能丢锚点后重建。 */
        public CurrentRead {
            if(rowVersion==0) { if(subjectKey!=null || keyVersion!=0 || state!=null) throw invalid(); }
            else { if(rowVersion<0 || keyVersion<=0 || state==null) throw invalid();digest(subjectKey); }
        }
        /** 首次订单只能由真实不存在查询返回此状态。 */
        public static CurrentRead absent() { return new CurrentRead(0,null,0,null); }
        /** 不输出解密聚合或主体索引。 */
        @Override public String toString() { return "OrderEvidenceCurrent[redacted]"; }
    }
    /** 锁内返回的最小CAS头；不含密文或需要网络的读取。 */
    public record LockedVersion(OrderKey order,long rowVersion,String subjectKey,long keyVersion,HistoryStamp history) {
        /** 与CurrentRead共享不存在语义，禁止nullable业务身份。 */
        public LockedVersion {
            Objects.requireNonNull(order);Objects.requireNonNull(history);
            if(rowVersion==0) { if(subjectKey!=null || keyVersion!=0) throw invalid(); }
            else { if(rowVersion<0 || keyVersion<=0) throw invalid();digest(subjectKey); }
        }
        /** CAS诊断不得间接输出主体HMAC。 */
        @Override public String toString() { return "LockedOrderEvidence[redacted]"; }
    }

    /** 合并候选及其原始许可、读证明；历史写入/Inbox/审计/任务的原子性留给后续持久服务。 */
    public record Prepared(LockedVersion expected,long acceptedKeyVersion,Instant issuedAt,Instant expiresAt,Instant preparedAt,MergeResult result) {
        /** Prepared只能引用明确的CAS证明；不是可以跳过锁后检查的持久授权。 */
        public Prepared { Objects.requireNonNull(expected);Objects.requireNonNull(issuedAt);Objects.requireNonNull(expiresAt);Objects.requireNonNull(preparedAt);Objects.requireNonNull(result); }
        /** 任一版本/历史/锚点变化或锁等待到期均重新回到事务外准备，不持锁执行KMS。 */
        public boolean mayCommit(LockedVersion locked,long anchorVersion,Instant now) {
            Objects.requireNonNull(now);
            return anchorVersion==acceptedKeyVersion && expected.equals(locked)
                    && !now.isBefore(preparedAt) && !now.isBefore(issuedAt) && now.isBefore(expiresAt);
        }
        /** 默认诊断不打印解密状态或原业务身份。 */
        @Override public String toString() { return "PreparedOrderEvidence[redacted]"; }
    }

    /**
     * 对实际读取到的完整历史调用SPI，绝不先插本次历史再把它当Seen。
     * Scope漂移由持久订单身份捕获并升级永久隔离，保留原State而非用新主体覆盖。
     */
    public static Prepared prepare(CurrentRead current,HistoryRead history,VerifiedInput input,Instant now,Duration maximumLifetime) {
        Objects.requireNonNull(current);Objects.requireNonNull(history);Objects.requireNonNull(input);Objects.requireNonNull(now);
        if(maximumLifetime==null || maximumLifetime.isZero() || maximumLifetime.isNegative()
                || Duration.between(input.issuedAt(),input.expiresAt()).compareTo(maximumLifetime)>0
                || now.isBefore(input.issuedAt()) || !now.isBefore(input.expiresAt())) throw invalid();
        if(history.revision()!=input.observation().snapshot().revision())throw invalid();
        OrderKey key=OrderKey.of(input.observation().snapshot().scope());
        if(current.state()!=null && (!key.equals(OrderKey.of(current.state().latest().scope())) || current.keyVersion()!=input.keyVersion())) throw invalid();
        var result=ReferralOrderEvidenceMerger.merge(current.state(),input.observation(),history.lookup(),now);
        boolean trustedTime=result.reason()!=Reason.SOURCE_UNVERIFIED && result.reason()!=Reason.FUTURE_OR_INCONSISTENT_TIME;
        if(current.state()!=null && trustedTime && (result.reason()==Reason.SCOPE_MISMATCH || !current.subjectKey().equals(input.subjectKey()))) {
            State old=current.state();
            result=new MergeResult(Outcome.CONFLICT,Reason.SCOPE_MISMATCH,new State(old.latest(),old.firstReceivedAt(),old.settledAtAnchor(),old.firstSettlementReceivedAt(),true,old.historyPendingRevision()));
        }
        var expected=new LockedVersion(key,current.rowVersion(),current.subjectKey(),current.keyVersion(),history.stamp());
        return new Prepared(expected,input.keyVersion(),input.issuedAt(),input.expiresAt(),now,result);
    }
    private static void text(String text) { if(text==null || text.isBlank())throw invalid(); }
    private static void digest(String text) { if(text==null || !text.matches("[0-9a-f]{64}"))throw invalid(); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("evidence preparation unavailable or inconsistent"); }
}
