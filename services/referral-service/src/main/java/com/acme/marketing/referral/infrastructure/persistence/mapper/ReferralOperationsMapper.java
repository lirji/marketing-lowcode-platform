package com.acme.marketing.referral.infrastructure.persistence.mapper;

import com.acme.marketing.referral.application.query.ReferralOperationsService.*;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;

/** 运营查询只走真实主库和有界seek分页，不在SQL或Java构造演示成功数据。 */
@Mapper
public interface ReferralOperationsMapper {
    /** 单SQL聚合，各子查询独立计数避免参与者/关系/奖励笛卡尔积放大。 */
    Counts summary(Map<String,Object> values);
    /** 按租户活动及当前组织/门店权限过滤参与者。 */
    List<Participant> participants(Map<String,Object> values);
    /** 关系与资格联查不暴露其他组织或主体明文。 */
    List<Relation> relations(Map<String,Object> values);
    /** 奖励返回独立状态维度，列表不解密受益人。 */
    List<Reward> rewards(Map<String,Object> values);
}
