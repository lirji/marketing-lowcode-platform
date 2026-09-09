package com.acme.marketing.referral.infrastructure.persistence.mapper;

import com.acme.marketing.referral.application.authorization.ReferralAuthorizationProofPort.Reward;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;

/** 首次确认和永久receipt的同库SQL，按参与者→资格→订单→任务→奖励顺序锁定。 */
@Mapper
public interface ReferralAuthorizationMapper {
    /** 事务外描述预读或最终锁定当前奖励，不能从请求重建固定字段。 */
    Reward reward(Map<String,Object> values);
    /** 当前参与者锁保证资格及奖励变化串行。 */
    String participant(Map<String,Object> values);
    /** 有界锁定资格依赖，超过部署预算时明确拒绝而非无界加载。 */
    List<Basis> qualifications(Map<String,Object> values);
    /** 先按资源ID排序锁当前订单，再检查投影水位。 */
    Evidence evidence(Map<String,Object> values);
    /** 最后锁任务，防止新fanout信号与首次确认并发越过。 */
    Task task(Map<String,Object> values);
    /** 参与者锁内获取人数，阶梯必须与真实有效人数一致。 */
    Long progress(Map<String,Object> values);
    /** 读取永久回执，不依赖旧候选或旧密钥仍有效。 */
    ReceiptRow receipt(Map<String,Object> values);
    /** 首次回执与奖励确认同事务保存，断点重试仍返回原ID。 */
    int insertReceipt(Map<String,Object> values);
    /** 有效且已预占才可首次确认，取消后永远拒绝。 */
    int confirm(Map<String,Object> values);
    /** 单关系资格依赖；会员/运行许可仍必须通过事务外权威证明。 */
    record Basis(String relationId,boolean counted,String state,long revision,String evidenceResourceId,long evidenceVersion,long taskRevision) {}
    /** 订单聚合最新水位和永久隔离状态。 */
    record Evidence(long version,boolean quarantined) {}
    /** 新信号不能被原资格回执掩盖。 */
    record Task(long requested,long processed,String status) {}
    /** 原始时间使用秒/纳秒永久往返，不因SQL微秒截断改变确认事实。 */
    record ReceiptRow(String rewardId,String sourceRequestId,String stableClaimsDigest,long qualificationRevision,String confirmationId,long authorizationSequence,long confirmedSeconds,int confirmedNanos) {}
}
