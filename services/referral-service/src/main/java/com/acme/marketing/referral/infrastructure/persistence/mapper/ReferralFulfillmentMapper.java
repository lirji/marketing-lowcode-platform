package com.acme.marketing.referral.infrastructure.persistence.mapper;

import java.util.Map;
import org.apache.ibatis.annotations.Mapper;

/** 可信履约历史/Inbox与当前奖励和配额同事务，旧事件不能覆盖更高版本。 */
@Mapper
public interface ReferralFulfillmentMapper {
    /** 永久事件原收据；异内容不能换键覆盖。 */
    Receipt inbox(Map<String,Object> values);
    /** 完整历史revision查重，不能只比较latest。 */
    String history(Map<String,Object> values);
    /** 当前来源固定及最高修订。 */
    Current current(Map<String,Object> values);
    /** 首次受信业务修订持久化稳定摘要。 */
    int insertHistory(Map<String,Object> values);
    /** 每次当前快照变化同时推进状态水位。 */
    int saveCurrent(Map<String,Object> values);
    /** 原事件结果持久化，旧事件重放保持原回执。 */
    int insertInbox(Map<String,Object> values);
    /** 只更新履约/补偿事实，不清除资格失效或取消授权。 */
    int updateReward(Map<String,Object> values);
    /** 最新可信累计快照，仅包含状态和证据摘要。 */
    record Current(String providerId,long providerRevision,String deliveryState,String compensationState,String businessDigest,String successDigest,String terminalDigest) {}
    /** 原事件结果不含个人信息或密钥。 */
    record Receipt(String rewardId,String digest,long revision,String outcome) {}
}
