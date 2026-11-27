# 营销操作审计验收

## OA-05 资金

- 资金库 Flyway V21 扩展尝试和结果。确认、释放、部分退款、剩余冲回和到期释放在账本事务保留结果。查询通过 `funding:reconcile` 和申请的租户、组织、门店权限校验。
- `GET /api/v1/promotion-applications/{applicationId}/operation-audits` 支持申请业务号及历史内部主键，返回分页结果。交易结算使用已验证的交易原生订单引用。已保存的重复操作关联原提交，历史应用缺少审计时允许关联为空。
- 真实 MySQL 的 `BenefitFundingIntegrationTest` 21 项通过。覆盖实际预算变动、状态和币种、重复回执、外租户拒绝、申请业务号查询。验证命令为 `./mvnw -B -pl services/benefit-funding-service -am -Dtest=BenefitFundingIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`。

## OA-06 发布

- 控制库 Flyway V17 追加受理、运行确认、拒绝以及重放关联。原审计链的算法不变，受理与终态各沿用自己的节点。组合发布与回滚由运行端回执确认，迟到结果复用原运行授权，只为同一提交确认一次。
- `GET /api/v1/campaigns/{campaignId}/release-bundles/operation-audits` 需要 `release:read`，并按当前组织、门店与活动隔离。可查可读业务编号、版本、运行序号、实际确认时间、链节点及重放关联。
- 已有排期暂停与恢复记录状态变化。已上线活动的独立暂停端点在当前控制面尚无生效闭环，后续接线需提供运行端确认时点。现有排期暂停事件仅表明排期状态。
- 真实 MySQL 验证命令为 `./mvnw -B -pl services/marketing-control-service -am -Dtest=ReleaseBundleReadinessIntegrationTest,ReleaseBundleIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`。

## OA-07 营销资金来源

`GET /api/v1/funding/trade-orders/{tradeTenant}/{nativeOrderId}/operation-audits` 从原生交易编号及交易租户查找已经冻结的资金申请，授权继续校验本地营销组织和全部门店。响应包含原试点交易报价引用、资金申请号、活动与实际决策代次，以及资金证据分页。MySQL 验证包含同一原生订单对应资金确认、重复回执、其他交易租户无匹配、其他门店拒绝。
