package com.acme.marketing.referral.infrastructure.persistence.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;

/** 永久奖励身份只在资格所属事务落库；所有SQL隔离在XML，禁止跨服务数据库读取。 */
@Mapper
public interface ReferralRewardMapper {
    /** 参与者已加锁后读取原受益人密文；不在锁内解密。 */
    SubjectRow subject(Map<String,Object> values);
    /** 查原永久身份，已失效也不能隐藏后重建。 */
    RewardRow reward(Map<String,Object> values);
    /** 当前关系及跌档奖励依规则及稳定ID排序锁定，保持多规则配额锁序，不扫描无关参与者。 */
    List<RewardRow> invalidatable(Map<String,Object> values);
    /** 唯一键和首次完整冻结声明一并保存，不使用INSERT IGNORE吞异常。 */
    int insert(Map<String,Object> values);
    /** 只推进当前资格修订；不更换规则、受益人、外部幂等键或已失效身份。 */
    int refresh(Map<String,Object> values);
    /** 失效与取消待办同事务；有授权/发送可能性时保留待补偿。 */
    int invalidate(Map<String,Object> values);
    /** 内部事件只含资源和状态，不含受益人摘要/密文/原值。 */
    int outbox(Map<String,Object> values);
    /** 追加首次建账和失效原因，失败时与资格及人数共同回滚。 */
    int audit(Map<String,Object> values);
    /** 固定主体密文，日志不输出任何索引或密钥引用。 */
    record SubjectRow(String subjectKey,long keyVersion,byte[] cipher,String encryptionKeyId) {
        public SubjectRow {cipher=cipher.clone();}
        @Override public byte[] cipher(){return cipher.clone();}
        @Override public String toString(){return "RewardSubject[redacted]";}
    }
    /** 内部状态只读，不是调用方可提交的授权证明。 */
    record RewardRow(String tenantId,String rewardId,String participantId,String relationId,String mode,long threshold,String policyHash,
            String ruleJson,String entitlementState,String authorizationState,String deliveryState,String compensationState,long revision,
            long qualificationRevision,long progressRevision) {}
}
