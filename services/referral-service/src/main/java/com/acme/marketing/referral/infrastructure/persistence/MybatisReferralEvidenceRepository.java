package com.acme.marketing.referral.infrastructure.persistence;
import com.acme.marketing.platform.crypto.Digests;
import com.acme.marketing.referral.application.evidence.*;
import com.acme.marketing.referral.application.evidence.ReferralEvidenceRepository.*;
import com.acme.marketing.referral.application.evidence.ProtectedReferralEvidencePort.*;
import com.acme.marketing.referral.application.evidence.ReferralEvidencePreparation.OrderKey;
import com.acme.marketing.referral.infrastructure.persistence.mapper.ReferralEvidenceMapper;
import com.acme.marketing.referral.infrastructure.persistence.mapper.ReferralEvidenceMapper.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;
/** 事实入账SQL只处理受保护内容；所有变更在调用方同一主库事务，失败不留下成功Inbox。 */
@Repository
public class MybatisReferralEvidenceRepository implements ReferralEvidenceRepository {
    private final ReferralEvidenceMapper mapper;private final ObjectMapper json;
    private static final DateTimeFormatter SQL_TIME=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS").withZone(ZoneOffset.UTC);
    /** Jackson只生成脱敏内部事件，不序列化规范主体证据。 */
    public MybatisReferralEvidenceRepository(ReferralEvidenceMapper mapper,ObjectMapper json){this.mapper=mapper;this.json=json;}
    /** 事务外读取原事件回执，不覆盖业务摘要。 */
    @Override public Inbox readInbox(EventKey key){return inbox(mapper.inbox(event(key,false)));}
    /** 事务外预读密文与真实聚合水位。 */
    @Override public StoredOrder readOrder(OrderKey key){return order(mapper.current(order(key,false)));}
    /** 指定历史真实查询，数据库异常由应用失败关闭，不能转成null。 */
    @Override public Sealed readHistory(OrderKey key,long revision){var values=order(key,false);values.put("revision",revision);return history(mapper.history(values));}
    /** Inbox锁位于订单锁之前，防止同事件跨订单反向加锁。 */
    @Override public Inbox lockInbox(EventKey key,String digest,OrderKey order,Instant now){transaction();var values=event(key,true);values.put("digest",digest);values.put("orderId",order.orderId());values.put("now",SQL_TIME.format(now));mapper.reserveInbox(values);return Objects.requireNonNull(inbox(mapper.inbox(values)));}
    /** 新行占位只在调用方事务内，后续有效State或整体回滚保证无孤立空行。 */
    @Override public StoredOrder lockOrder(OrderKey key,String resourceId,Instant now){transaction();var values=order(key,true);values.put("resourceId",resourceId);values.put("now",SQL_TIME.format(now));mapper.reserveOrder(values);return Objects.requireNonNull(order(mapper.current(values)));}
    /** current锁后查历史槽，提供独立于事务外expected的CAS依据。 */
    @Override public Sealed lockHistory(OrderKey key,long revision){transaction();var values=order(key,true);values.put("revision",revision);return history(mapper.history(values));}
    /** 首次接收纳秒独立保留，扫描时间列向下取微秒不改事实原值。 */
    @Override public void insertHistory(Sealed snapshot,Instant receivedAt,Instant now){transaction();var values=sealed(snapshot);values.put("receivedAt",SQL_TIME.format(receivedAt));values.put("receivedSeconds",receivedAt.getEpochSecond());values.put("receivedNanos",receivedAt.getNano());values.put("now",SQL_TIME.format(now));one(mapper.insertHistory(values));}
    /** 精确CAS推进状态、AAD隔离标志和水位；溢出不静默回绕。 */
    @Override public long replaceOrder(StoredOrder before,Sealed state,Instant now){transaction();long version=Math.addExact(before.rowVersion(),1);var values=sealed(state);values.put("resourceId",before.resourceId());values.put("expectedVersion",before.rowVersion());values.put("nextVersion",version);values.put("now",SQL_TIME.format(now));one(mapper.replaceOrder(values));return version;}
    /** 回执仅完成一次，不随后续退款/隔离改写原结果。 */
    @Override public void completeInbox(EventKey key,Result result,Instant now){transaction();var values=event(key,true);values.put("resourceId",result.resourceId());values.put("outcome",result.outcome());values.put("reason",result.reason());values.put("stateVersion",result.stateVersion());values.put("now",SQL_TIME.format(now));one(mapper.completeInbox(values));}
    /** 状态变化持久化待扫描目标、审计和内部Outbox，不产生qualification/reward写入。 */
    @Override public void changed(StoredOrder before,long version,String reason,String actor,String trace,Instant now){
        transaction();var values=eventValues(before,actor,trace,now);values.put("version",version);values.put("reason",reason);values.put("action","ORDER_EVIDENCE_CHANGED");
        String payload=json.writeValueAsString(Map.of("schemaVersion",1,"type","ORDER_EVIDENCE_CHANGED","resourceId",before.resourceId(),"stateVersion",version,"reason",reason,"projectionState","PENDING"));
        values.put("payload",payload);values.put("payloadHash",Digests.sha256Hex(payload));mapper.fanout(values);one(mapper.audit(values));one(mapper.outbox(values));
    }
    /** 原回执异内容拒绝追加独立审计；数据里不保留原报文或解密主体。 */
    @Override public void conflict(StoredOrder original,String actor,String trace,Instant now){transaction();var values=eventValues(original,actor,trace,now);values.put("reason","INBOX_CONTENT_CONFLICT");values.put("action","EVIDENCE_INBOX_CONFLICT");values.put("payloadHash",Digests.sha256Hex(json.writeValueAsString(Map.of("resourceId",original.resourceId(),"reason","INBOX_CONTENT_CONFLICT"))));one(mapper.audit(values));}
    private Map<String,Object> eventValues(StoredOrder order,String actor,String trace,Instant now){var values=new HashMap<String,Object>();values.put("tenant",order.key().tenantId());values.put("resourceId",order.resourceId());values.put("actor",actor);values.put("trace",trace);values.put("auditId",UUID.randomUUID().toString());values.put("eventId",UUID.randomUUID().toString());values.put("now",SQL_TIME.format(now));return values;}
    private static Map<String,Object> event(EventKey key,boolean lock){var values=new HashMap<String,Object>();values.put("tenant",key.tenantId());values.put("issuer",key.issuer());values.put("source",key.sourceSystem());values.put("eventId",key.eventId());values.put("lock",lock);return values;}
    private static Map<String,Object> order(OrderKey key,boolean lock){var values=new HashMap<String,Object>();values.put("tenant",key.tenantId());values.put("source",key.sourceSystem());values.put("orderId",key.orderId());values.put("lock",lock);return values;}
    private static Map<String,Object> sealed(Sealed sealed){var h=sealed.header();var values=order(h.order(),true);values.put("subject",h.subjectKey());values.put("keyVersion",h.keyVersion());values.put("organization",h.organizationId());values.put("shop",h.shopId());values.put("revision",h.revision());values.put("quarantined",h.quarantined());values.put("cipher",sealed.cipher());values.put("keyId",sealed.keyId());values.put("digest",sealed.businessDigest());return values;}
    private static Inbox inbox(InboxRow row){return row==null?null:new Inbox(new EventKey(row.tenant(),row.issuer(),row.source(),row.eventId()),row.businessDigest(),new OrderKey(row.tenant(),row.source(),row.orderId()),row.resourceId()==null?null:new Result(row.resourceId(),row.outcome(),row.reason(),row.stateVersion()));}
    private static StoredOrder order(OrderRow row){if(row==null)return null;var key=new OrderKey(row.tenant(),row.source(),row.orderId());return new StoredOrder(key,row.resourceId(),row.rowVersion(),row.rowVersion()==0?null:new Sealed(new Header(key,row.subject(),row.keyVersion(),row.organization(),row.shop(),row.revision(),Purpose.CURRENT,row.quarantined()),row.cipher(),row.keyId(),row.businessDigest()));}
    private static Sealed history(CipherRow row){return row==null?null:new Sealed(new Header(new OrderKey(row.tenant(),row.source(),row.orderId()),row.subject(),row.keyVersion(),row.organization(),row.shop(),row.revision(),Purpose.HISTORY,false),row.cipher(),row.keyId(),row.businessDigest());}
    private static void transaction(){if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("evidence writes require owning transaction");}
    private static void one(int rows){if(rows!=1)throw new IllegalStateException("evidence persistence invariant failed");}
}
