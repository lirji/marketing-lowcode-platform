package com.acme.marketing.referral.application.evidence;

import com.acme.marketing.referral.application.evidence.ProtectedReferralEvidencePort.Sealed;
import com.acme.marketing.referral.application.evidence.ReferralEvidencePreparation.OrderKey;
import java.time.Instant;
import java.util.Objects;

/** 订单事实账本的短事务边界；事实事务不获取participant/relation锁，避免fanout反序。 */
public interface ReferralEvidenceRepository {
    /** 事务外预读永久事件回执，后续必须锁定再次确认。 */
    Inbox readInbox(EventKey key);
    /** 事务外预读当前密文和rowVersion，仅用于准备，不能作为写入授权。 */
    StoredOrder readOrder(OrderKey key);
    /** 真正查询指定业务revision，异常必须传播为不可用，不能吞掉当不存在。 */
    Sealed readHistory(OrderKey key,long revision);
    /** 锁序第一项由调用方复用租户索引锚点，随后先锁Inbox；占位不能独立提交。 */
    Inbox lockInbox(EventKey key,String digest,OrderKey order,Instant now);
    /** 单订单current行串行化该订单所有current/history写入，缺失时同事务占位。 */
    StoredOrder lockOrder(OrderKey key,String proposedResourceId,Instant now);
    /** current锁后重新查询历史存在性/摘要，用实际存储重建CAS，不能返回expected假证明。 */
    Sealed lockHistory(OrderKey key,long revision);
    /** 本次首次业务revision完整历史入库；current锁避免同订单历史竞态。 */
    void insertHistory(Sealed snapshot,Instant receivedAt,Instant now);
    /** 仅按旧rowVersion更新，header和密文同写，不允许覆盖已变化的聚合。 */
    long replaceOrder(StoredOrder before,Sealed state,Instant now);
    /** 当前回执完成与历史/聚合/任务同事务，永久保留原处理结果。 */
    void completeInbox(EventKey key,Result result,Instant now);
    /** 持久fanout目标水位及内部Outbox、审计同事务；不直接查询或锁所有活动关系。 */
    void changed(StoredOrder before,long version,String reason,String actor,String trace,Instant now);
    /** 异内容事件的拒绝审计追加，不覆盖原成功回执；不存载荷/原主体。 */
    void conflict(StoredOrder original,String actor,String trace,Instant now);

    /** 事件ID必须带认证issuer和source命名空间，不能全局去重而串租户。 */
    record EventKey(String tenantId,String issuer,String sourceSystem,String eventId) {
        /** 真实发行者由来源Port返回，所有键精确匹配、不归一化。 */
        public EventKey { text(tenantId);text(issuer);text(sourceSystem);text(eventId); }
        /** 防止默认日志输出来源事件标识。 */
        @Override public String toString(){return "ReferralEvidenceEventKey[redacted]";}
    }
    /** Inbox尚未提交时result可空；读取到已提交空result必须作为不一致拒绝。 */
    record Inbox(EventKey key,String businessDigest,OrderKey originalOrder,Result result) {
        /** 摘要永不覆盖，事件所属订单永久固定。 */
        public Inbox { Objects.requireNonNull(key);Objects.requireNonNull(originalOrder);digest(businessDigest); }
        /** 不输出内部业务摘要和来源。 */
        @Override public String toString(){return "ReferralEvidenceInbox[redacted]";}
    }
    /** 永久聚合资源；rowVersion0只用于当前事务内新行占位，不允许提交空State。 */
    record StoredOrder(OrderKey key,String resourceId,long rowVersion,Sealed state) {
        /** 正式已存在聚合必须有完整受保护状态，不能靠latest字段补锚点。 */
        public StoredOrder {
            Objects.requireNonNull(key);text(resourceId);
            if(rowVersion<0 || (rowVersion==0)!=(state==null))throw invalid();
            if(state!=null && !key.equals(state.header().order()))throw invalid();
        }
        /** 不输出HMAC、Scope或加密内容。 */
        @Override public String toString(){return "StoredReferralOrderEvidence[redacted]";}
    }
    /** 接收结果只表示事实账本状态，不表示活动资格或奖励；同事件永久返回原值。 */
    record Result(String resourceId,String outcome,String reason,long stateVersion) {
        /** 成功回执必须指向已提交的非空当前聚合修订。 */
        public Result { text(resourceId);text(outcome);text(reason);if(stateVersion<=0)throw invalid(); }
    }
    private static void text(String text){if(text==null || text.isBlank())throw invalid();}
    private static void digest(String text){if(text==null || !text.matches("[0-9a-f]{64}"))throw invalid();}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("evidence persistence state inconsistent");}
}
