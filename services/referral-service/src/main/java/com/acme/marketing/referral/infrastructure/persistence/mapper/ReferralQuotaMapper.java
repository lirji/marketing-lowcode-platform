package com.acme.marketing.referral.infrastructure.persistence.mapper;

import java.util.Map;
import org.apache.ibatis.annotations.Mapper;

/** 配额冷热路径SQL边界；热路径不更新全活动总账户。 */
@Mapper
public interface ReferralQuotaMapper {
    /** 无锁读取仅定位参与者，最终业务判断必须锁后重读。 */
    RewardRow reward(Map<String,Object> values);
    /** 统一先锁参与者，与资格/取消遵守相同顺序。 */
    String lockParticipant(Map<String,Object> values);
    /** 永久总账户首次创建，重复只保留原配置。 */
    int reserveAccount(Map<String,Object> values);
    /** 配置校验及冷路径可锁账户，热路径只读。 */
    AccountRow account(Map<String,Object> values);
    /** 初始化每个桶，和总账户在一个事务。 */
    int insertBucket(Map<String,Object> values);
    /** 桶当前读带行锁，真实余额不能使用缓存。 */
    BucketRow bucket(Map<String,Object> values);
    /** 锁当前用户限额，跨桶共享同一权威行。 */
    int reserveSubject(Map<String,Object> values);
    /** 已占用与已消耗共同受个人上限约束。 */
    SubjectRow subject(Map<String,Object> values);
    /** 永久reservation按reward唯一，查出终态后不再占额。 */
    ReservationRow reservation(Map<String,Object> values);
    /** epoch及rowVersion共同CAS，调拨后的旧写入失败。 */
    int updateBucket(Map<String,Object> values);
    /** 预占和返还个人限额与桶同事务。 */
    int updateSubject(Map<String,Object> values);
    /** 永久保存首次预占，不自动按时间清理。 */
    int insertReservation(Map<String,Object> values);
    /** quota状态变化加入奖励CAS及可靠事件。 */
    int reserveReward(Map<String,Object> values);
    /** 仅可靠本地未授权未发送取消，永久保留释放记录。 */
    int releaseReservation(Map<String,Object> values);
    /** 奖励释放状态CAS，不改变永久身份或历史取消栅栏。 */
    int releaseReward(Map<String,Object> values);
    /** 保存真实终态和此前成功摘要，重复回调不再次移动计数。 */
    int finalizeReservation(Map<String,Object> values);
    /** 成功/明确未发/追回后的数量状态与外部事实同事务。 */
    int finalizeReward(Map<String,Object> values);
    /** 当前资格/证据/任务不齐全时禁止新预占。 */
    long staleQualifications(Map<String,Object> values);
    /** 获取当前有效人数；参加者锁保护其变化。 */
    Long validCount(Map<String,Object> values);
    /** 按当前reward记录个人及桶配置，避免通过换桶绕限额。 */
    record RewardRow(String tenantId,String rewardId,String campaignId,String organizationId,String shopId,String participantId,String relationId,
            String beneficiaryKey,String ruleId,String ruleJson,String mode,long threshold,String entitlementState,String quotaState,long revision,String authorizationState,String deliveryState) {
        @Override public String toString(){return "QuotaReward[redacted]";}
    }
    /** 总额度配置永久固定；热路径只读，不承担每份奖励的计数热点。 */
    record AccountRow(String organizationId,String shopId,String ruleJson,long quotaLimit,int bucketCount) {}
    /** 桶计数精确守恒，epoch用于调拨隔离。 */
    record BucketRow(long allocated,long available,long reserved,long consumed,long epoch,long version) {}
    /** 同受益人在整个活动规则下共享限额，不能按bucket重置。 */
    record SubjectRow(long quotaLimit,long reserved,long consumed,long version) {}
    /** 终态预占也必须保留，禁止同奖励重新消耗配额。 */
    record ReservationRow(String participantId,String beneficiaryKey,int bucketId,long createdEpoch,String state,String terminalFact,String terminalDigest,String successDigest,long revision) {
        @Override public String toString(){return "QuotaReservationRow[redacted]";}
    }
}
