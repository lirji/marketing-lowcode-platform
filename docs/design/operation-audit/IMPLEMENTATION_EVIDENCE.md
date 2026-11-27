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

## OA-09B 与 OA-09C 营销审计档案

资金 V22 和发布 V18 各自登记档案清单、受控工单与临时可查副本。每批最多 200 条，按单租户和明确业务引用领取。对象格式为版本化 NDJSON.gz。相同脱敏快照复用批次，回读比对字节和摘要后释放临时写入内容。恢复与读取分别留痕，须核对当前组织、门店和人员工单。临时副本有效期受工单约束，最长七天。两个来源的桶前缀、写入身份、读取身份和 XXL-JOB 执行器独立配置。

- 资金作业 `marketingFundingAuditArchiveVerify` 按原资金申请 ID 归档，证据保留已验真的原交易号、结算引用和幂等效果。查阅路径为 `/api/v1/funding/audit-archives/{ticket}`，对该路径追加 `/restore` 执行恢复。
- 发布作业 `marketingReleaseAuditArchiveVerify` 接收 `活动编号@发布版本`，保留入口、受理、可信终态、重试和原控制审计链引用。查阅路径为 `/api/v1/releases/audit-archives/{ticket}`，对该路径追加 `/restore` 执行恢复。隔离测试对同一版本保留两份档案，后续回滚后旧档案仍能精确回读。
- V23 资金在线切换要求独立批次批准、受审最晚事实时间、有效对象版本和当前案件保全检查。`marketingFundingAuditArchiveOnlineCutover` 按明确批次在短事务内处理经锁定并匹配的审计行。原命令重放继续引用同一效果，预算账本仍由资金域处理。 [MySQL 锁定读](https://dev.mysql.com/doc/refman/8.4/en/select.html)
- 已确认切换凭证保存到数据库备份域外。源库中断后用 `marketingFundingAuditArchiveRepairProof` 补发。隔离恢复先用 `marketingFundingAuditArchiveRecoveryInventory` 逐页清点，再以精确版本、长度和摘要运行 `marketingFundingAuditArchiveReplayRecovery`。恢复身份为准备前缀增加列表权限，并对提交凭证前缀增加元数据读取权限。存在未决准备记录或数据冲突时，保持恢复目标隔离并交由对应负责人复核。
- 发布归档、资金在线切换和隔离补放各按来源开关启用。正式范围由 OA-10 签收；资金与发布模块按独立配置收敛权限。
