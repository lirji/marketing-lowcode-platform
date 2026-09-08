package com.acme.marketing.referral.infrastructure.persistence.mapper;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
/** 只含本地主库SQL；加解密与规范证据合并由事务外应用准备完成。 */
@Mapper
public interface ReferralEvidenceMapper {
    /** 永久Inbox只在同事务新增占位，不覆盖原摘要。 */
    int reserveInbox(Map<String,Object> values);
    /** 根据lock参数读取实际数据库回执，不能伪造CAS证明。 */
    InboxRow inbox(Map<String,Object> values);
    /** 单订单首次占位，与有效State或整体回滚一同结束。 */
    int reserveOrder(Map<String,Object> values);
    /** tenant/source/order唯一当前聚合的非锁定预读或锁定读。 */
    OrderRow current(Map<String,Object> values);
    /** 指定真实业务revision历史的非锁定预读或锁定读。 */
    CipherRow history(Map<String,Object> values);
    /** 完整受保护历史仅新增，唯一冲突不能覆盖。 */
    int insertHistory(Map<String,Object> values);
    /** 按已锁定rowVersion作CAS，header与受保护完整State一起推进。 */
    int replaceOrder(Map<String,Object> values);
    /** 原事件完成凭据永久写一次。 */
    int completeInbox(Map<String,Object> values);
    /** 单订单持久fanout水位，不同步获取活动参与锁。 */
    int fanout(Map<String,Object> values);
    /** 内部证据变更Outbox，不发布资格或奖励成功。 */
    int outbox(Map<String,Object> values);
    /** 事实变更或拒绝审计，不写报文/原主体。 */
    int audit(Map<String,Object> values);
    /** Mapper内部密文行，不会被直接作为服务响应。 */
    record CipherRow(String tenant,String source,String orderId,String subject,Long keyVersion,String organization,String shop,Long revision,byte[] cipher,String keyId,String businessDigest){@Override public String toString(){return "EvidenceCipherRow[redacted]";}}
    /** rowVersion0仅为当前事务占位，正式记录不得返回空密文。 */
    record OrderRow(String tenant,String source,String orderId,String resourceId,long rowVersion,boolean quarantined,String subject,Long keyVersion,String organization,String shop,Long revision,byte[] cipher,String keyId,String businessDigest){@Override public String toString(){return "EvidenceCurrentRow[redacted]";}}
    /** 回执原订单与原结果独立于最新订单状态。 */
    record InboxRow(String tenant,String issuer,String source,String eventId,String businessDigest,String orderId,String resourceId,String outcome,String reason,Long stateVersion){@Override public String toString(){return "EvidenceInboxRow[redacted]";}}
}
