package com.acme.marketing.referral.application.query;

import com.acme.marketing.referral.application.query.ReferralOperationsService.*;
import java.util.List;
import java.util.Map;

/** 裂变运营的有界分页及聚合查询端口，所有条件包含可信租户和数据范围。 */
public interface ReferralOperationsRepository {
    /** 单SQL聚合，各子查询独立计数避免参与者/关系/奖励笛卡尔积放大。 */
    Counts summary(Map<String,Object> values);
    /** 按租户活动及当前组织/门店权限过滤参与者。 */
    List<Participant> participants(Map<String,Object> values);
    /** 关系与资格联查不暴露其他组织或主体明文。 */
    List<Relation> relations(Map<String,Object> values);
    /** 奖励返回独立状态维度，列表不解密受益人。 */
    List<Reward> rewards(Map<String,Object> values);
}
