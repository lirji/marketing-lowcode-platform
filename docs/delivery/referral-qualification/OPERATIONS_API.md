# 裂变运营查询合同（2026-09-08）

本次已新增四个只读HTTP入口；尚未部署或完成Cursor真实联调。所有请求经过现有认证链，必须有 `referral:read`，同时按令牌tenant、organizations、shops过滤。没有可见数据返回空列表，不揭露其他范围资源是否存在。

| GET路径 | 数据 |
|---|---|
| `/api/v1/referral-campaigns/{campaignId}/participants` | 固定参与版本及当前/历史人数 |
| `/api/v1/referral-campaigns/{campaignId}/relations` | 永久邀请关系及当前资格、原因、证据水位 |
| `/api/v1/referral-campaigns/{campaignId}/rewards` | 永久奖励身份、角色、规则和正交状态 |
| `/api/v1/referral-campaigns/{campaignId}/summary` | 当前数据库投影汇总（非事件消费水位） |

三个列表参数：`after`为上一页返回的`nextCursor`；`limit`默认20、范围1–100。响应统一为 `{items,nextCursor,asOf,consistency:"LIVE_DATABASE"}`。`nextCursor=null`表示本次查询没有下一页。游标按资源ID排序，不是创建时间排序；跨页不保证同一个事务快照，`asOf`只表示查询结束时间，不是Kafka全局消费水位。

participants字段：participantId/campaignId/organizationId/shopId/definitionId/definitionVersion/generation/state/createdAt/validCount/everQualifiedCount/progressRevision。尚未计算的后三项为null。

relations字段：relationId/participantId/boundAt/deadlineAt/state/qualificationState/reason/counted/everQualified/qualificationRevision/evidenceVersion。尚未评估时资格字段为null，`BOUND`不能显示为达标。

rewards字段：rewardId/participantId/relationId/role/mode/ruleId/threshold/entitlementState/authorizationState/riskState/deliveryState/compensationState/quotaState/revision/createdAt。阶梯relationId为空；角色和每份奖励独立。`WAIT_QUOTA`是建账后待预占，`RESERVED`是数量占用，`CONFIRMED`是持久授权；都不表示实际发券。`UNKNOWN`保持待对账，`CANCEL_REQUESTED`/`PENDING`保持待追回。只有真实终态回流才能将deliveryState标为SUCCEEDED，V10已有内部可信终态入箱服务，真实渠道适配仍未接通。

所有DTO不含用户原文、HMAC、token、密文和签名。不要依赖内部Java实体序列化。金额/数量/版本由前端按int64安全规则处理；响应不提供直接修改资格或成功状态的入口。

网关新增referralCircuit和上述只读路径，`REFERRAL_SERVICE_URL`默认localhost:8090；容器部署需配置真实容器地址。网关不转发internal确认或参与接口；另新增仅POST的受控复评路由，见[复评合同](../referral-quota/REEVALUATION_API.md)。

summary响应：`{counts,asOf,consistency:"DATABASE_PROJECTION",eventWatermark:null}`。counts字段：participants、relations、projectedValidRelations、everQualifiedRelations、awaitingEvaluation、rewards、succeededRewards、invalidatedRewards、pendingCompensation、reversedRewards、manualReviewRewards。关系与奖励分别计数，双边/阶梯奖励不能当作人数；SUCCEEDED和REVERSED可以同时累计。awaitingEvaluation统计未投影或任务未完成，不是所有外部事件积压；请求失败不能显示为零。单条SQL具有一致数据库快照，查询超时5秒，但不保证热点大活动容量达标。

主合同已引用 `marketing-contracts/src/main/resources/openapi/referral-operations.yaml`。通配组织/门店权限遵循现有TenantScope语义，空权限返回空结果。

BE-03四个只读资源已实现；BE-01新增活动类型，见 `../referral-quota/CAMPAIGN_TYPE_API.md`。BE-04受控复评已交付排队合同；BE-02发布、BE-05真实履约适配、BE-06 C端BFF、measurement仍待继续。
