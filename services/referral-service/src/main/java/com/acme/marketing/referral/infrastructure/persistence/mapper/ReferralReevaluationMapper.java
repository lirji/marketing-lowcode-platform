package com.acme.marketing.referral.infrastructure.persistence.mapper;

import java.time.Instant;
import java.util.*;
import org.apache.ibatis.annotations.Mapper;

/** 复评只登记永久操作与目标关系，SQL不提供覆盖资格或奖励状态的入口。 */
@Mapper
public interface ReferralReevaluationMapper {
    /** 按已认证操作者隔离幂等键，调用方已锁定对应参与者与奖励。 */
    Stored receipt(Map<String,Object> values);
    /** 阶梯需重算同参与者所有关系；数量超过预算时整笔拒绝。 */
    List<String> relations(Map<String,Object> values);
    /** 操作登记与排队、审计、Outbox共同提交。 */
    int insert(Map<String,Object> values);
    /** 永久收据表示已排队，不能据此声明完成或实际发奖。 */
    record Stored(String operationId,String rewardId,String digest,String state,int relationCount,Instant createdAt) {}
}
