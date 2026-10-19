# 13 决策解释、调试与恢复视图

## 2026-10-19 更新

控制台列表、表单、时间、设计器可读性已改善；完整跨系统因果诊断及恢复操作仍未按本章合同整体验收。

当前源码核对基线 `6b9092f`；[完整状态与证据](../marketing-platform-analysis/CURRENT_STATUS.md)。以下保留原评审基线的发现、严重度和测试结果；其中“当前/待实施”均指原评审当时。历史源码链接已固定到原提交，后续实现请从最新状态索引进入。

## 历史报告正文（1392ca9 基线）

评审日期：2026-09-22；源码基线：`1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c` 加现有工作区。模式：`READ_ONLY_ARCHITECTURE_REVIEW`。

`CONFIRMED` 为直接源码或明确测试证据，`INFERRED` 为推断和建议，`UNKNOWN` 为待补证据。所有目标模型、任务与验收均为规划，不表示已经实现或授权执行。

## 当前能力与缺口

CONFIRMED：Decision candidates有拒绝原因，GraphValidator有nodeId/pointer/code，仿真有trace，Enrollment有节点/版本，发布有审计/指令，奖励有风险/投递状态。UNKNOWN：能否在当前部署查询完整跨链因果。先修A01再扩大模拟器，否则更漂亮的解释只是错误语义的展示。

[DecisionApplicationService.java:71](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/offer-decision-service/src/main/java/com/acme/marketing/decision/application/DecisionApplicationService.java#L71)、[GraphSimulationService.java:39](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/marketing-control-service/src/main/java/com/acme/marketing/control/application/GraphSimulationService.java#L39)、[MeasurementService.java:1](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/measurement-service/src/main/java/com/acme/marketing/measurement/application/MeasurementService.java#L1)。

## Marketing Decision Trace（目标模型）

|字段组|设计|约束|
|---|---|---|
|身份|tenant、executionId、requestId、subject受控引用|服务端权限；不将明文PII放metrics|
|程序|campaign/definition version、artifact checksum、generation、activationSequence、ABI|记录实际执行版本，不只当前控制面期望|
|事实|AudienceRef、membershipVersion、factSource、asOf、freshness|可脱敏摘要，不能复制全部画像|
|步骤|stage/node、PASS/FAIL/UNKNOWN/SKIPPED、reasonCode、source pointer|NO_MATCH与系统故障分开|
|结果|offers/金额分摊、commandId、external result reference|账本/履约Owner提供权威终态|
|时序|eventTime、ingestedAt、executedAt、projectedAt|显式投影滞后，不按时间排序伪造因果|
|诊断|errorCategory、retryable、attemptId、recovery reference|运维动作单独授权且审计|

示例（假设用例，非实测用户）：Audience PASS，Rule NO_MATCH reason=TIER_MISMATCH，Journey SKIPPED。若membership数据过期则Audience UNKNOWN/FRESHNESS_EXCEEDED，不能写成用户不属于群体。traceId只用于关联，不能代替永久业务幂等键。

## Debug能力矩阵

|能力|已有|目标验收|
|---|---|---|
|Rule Simulator|有，但Offer不同源|同输入/同制品仿真与在线纯计算一致|
|Audience Preview|请求样本计算|标注sample size/source/asOf，不能称全量估计|
|Journey Dry Run|纯Runtime可测试；运营全链dry-run未确认|虚拟时钟/固定事实、隔离effect sink，零真实发放|
|Release Preview|stage/制品/ACK状态|展示依赖diff、旧实例兼容、成员模式、运行观察代次|
|Runtime Trace|分散日志/状态/测量查询|一个execution可定位拒绝、timer耗尽和渠道UNKNOWN|

## A12：状态与因果解释分散

- Problem：运营无法仅凭一个COMPLETED/SENT判断营销效果；跨链解释完整性未确认。
- Evidence：CONFIRMED状态差异见[JourneyRuntime.java:77](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/runtime-spi/journey-runtime-spi/src/main/java/com/acme/marketing/journey/JourneyRuntime.java#L77)与[EngagementService.java:147](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/engagement-service/src/main/java/com/acme/marketing/engagement/application/EngagementService.java#L147)；统一页面能力UNKNOWN。
- Impact：INFERRED，重复人工补发或误判回滚成功，排障时间增加；无MTTR实测。
- Root Cause：独立领域状态合理，但缺少只读因果关联和可见的UNKNOWN。
- Current Workaround：按多个ID查audit、enrollment、command、reward和measurement。
- Recommended Direction：T07关联字段/查询，再T09受控恢复，不先建全新日志平台。
- Trade-off：存储/隐私/保留成本；全量明细不可无限保留，关键拒绝与奖励审计不能随意采样丢失。
- Priority：P1（恢复正确性）/P2（诊断体验）。

观测落地：业务指标用低基数stage/reason/status；单用户查询走鉴权索引视图。采集timer lag、activation skew、outbox age、UNKNOWN age、对账差额、membership freshness、checkpoint duration。每个告警绑定15章故障处置；没有明确业务目标前阈值标TBD，不虚构SLO。

## 继承证据

- [上一阶段 14-observability-recovery](../marketing-platform-analysis/14-observability-recovery.md)：作为发现输入；本章新增判断以定向源码复核为准。
- [上一阶段 18-interview-assets](../marketing-platform-analysis/18-interview-assets.md)：作为发现输入；本章新增判断以定向源码复核为准。
