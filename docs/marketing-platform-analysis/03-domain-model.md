# 03 核心领域模型与状态所有权

## 2026-10-19 更新

原领域所有权继续适用；Campaign 业务状态、Definition 与 Release 的完整聚合规则仍待业务确认。

当前源码核对基线 `6b9092f`；[完整状态与证据](CURRENT_STATUS.md)。以下保留原评审基线的发现、严重度和测试结果；其中“当前/待实施”均指原评审当时。历史源码链接已固定到原提交，后续实现请从最新状态索引进入。

## 历史报告正文（1392ca9 基线）

- 仓库：`marketing-lowcode-platform`；源码基线：`1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c` 加当前未提交工作区。
- 分析日期：2026-09-22；模式：`READ_ONLY_ANALYSIS`。
- `CONFIRMED`＝源码/配置/测试直接证据；`INFERRED`＝据证据推断；`UNKNOWN`＝尚缺证据。代码存在不等于已部署或已通过真实链路验收。
- 本章遵循用户提示词；产品代码、数据库、运行配置、Git 提交与推送均不变。

## 对象映射

|提示词概念|真实对象|身份/版本|职责与边界|
|---|---|---|---|
|Activity|Campaign|tenantId + id；type 创建固定|目标、范围和组织；没有独立 ActivityTemplate 主模型证据|
|ActivityConfig/Version|DefinitionVersion + GraphDefinition|tenant + definitionId + version|分方言不可变配置；不等于 Campaign 全量版本|
|Rule|OfferPolicy / PromotionFormula / DRL / DMN|定义版本→制品摘要/ABI|多种实现，不能合称单一规则 DSL|
|Audience|SegmentRule / SnapshotView / Membership|segment version、snapshotId、membership version|人群表达式、成员集合和新鲜度分层|
|WorkflowDefinition|JourneyPlan|journeyId + version|编译后节点和路由|
|WorkflowInstance|EnrollmentSnapshot|tenant + enrollmentId|固定 journeyVersion、currentNodeId、variables、去重集合|
|Release|ReleaseManifest + ActivationDirective|slot + generation；activationSequence|安装制品与切流指令分离|
|Reward|ResourceAccount、PromotionApplication、AwardIntent、ReferralReward|各自业务键|预占账本、投递意图、裂变永久奖励不是同一状态|

证据：[Campaign.java:1](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/marketing-control-service/src/main/java/com/acme/marketing/control/domain/Campaign.java#L1)、[GraphDefinition.java:1](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/runtime-spi/lowcode-language-core/src/main/java/com/acme/marketing/lowcode/model/GraphDefinition.java#L1)、[EnrollmentSnapshot.java:1](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/runtime-spi/journey-runtime-spi/src/main/java/com/acme/marketing/journey/EnrollmentSnapshot.java#L1)、[ReleaseApplicationService.java:110](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/marketing-control-service/src/main/java/com/acme/marketing/control/application/ReleaseApplicationService.java#L110)、[BenefitFundingService.java:1012](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/benefit-funding-service/src/main/java/com/acme/marketing/benefit/application/BenefitFundingService.java#L1012)。

## 业务不变量

1. **CONFIRMED**：跨租户请求不可替换制品/报价 tenant；发布 slot 固定环境、cell、runtime、namespace。
2. **CONFIRMED**：定义语义相同重用旧版本，语义改变追加版本；当前运行读取已安装代次。
3. **CONFIRMED**：Enrollment 固定旅程版本；新事件不能带另一 PlanReference 替换它。
4. **CONFIRMED**：相同幂等键而内容不同返回冲突；旧 fence 不能覆写新领取结果。
5. **CONFIRMED**：折扣/出资按最小货币单位分配，资源更新校验 version，账本和约束维护资源完整性。
6. **INFERRED**：这些局部机制构成端到端正确性的基础；尚不能证明所有下游系统共同保持不变量。

[JourneyJobReducer.java:33](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/jobs/journey-runtime-flink-job/src/main/java/com/acme/marketing/jobs/journey/JourneyJobReducer.java#L33)、[AwardIntentService.java:87](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/benefit-funding-service/src/main/java/com/acme/marketing/benefit/application/AwardIntentService.java#L87)、[LargestRemainderAllocator.java:39](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/runtime-spi/decision-runtime-spi/src/main/java/com/acme/marketing/decision/pricing/LargestRemainderAllocator.java#L39)。

## 多套状态机不能混写

|模型|可确认状态/迁移|限制|
|---|---|---|
|Campaign 领域对象|DRAFT→IN_REVIEW→APPROVED→ACTIVE→PAUSED|ENDED 有枚举；未找到完整持久化生命周期接线|
|Definition 应用服务|DRAFT→VALIDATED→IN_REVIEW→APPROVED；拒绝回 DRAFT|领域枚举还有 RELEASED/REJECTED，不代表应用均使用|
|ApprovalCase|OPEN→APPROVED / REJECTED|按所需角色与四眼规则推进|
|Release|STAGED→ACTIVE→ROLLED_BACK|回滚返回旧目标 ACTIVE，当前 manifest 标记回滚|
|Enrollment|RUNNING↔WAITING，→COMPLETED / FAILED|终态 advance 不继续；过期投影处理另见运行章节|
|Award delivery|PENDING→SENDING→SENT；失败 PENDING 或 DEAD|SENT 的 deliveryResult 需区分模式，不能一概当券到账|
|Referral preparation|PREPARED、CONFIRMING、CONFIRM_UNKNOWN、CONFIRMED、ACCEPTED、REJECTED|UNKNOWN 需要原身份恢复，不可重新发一份|

[ControlApplicationService.java:218](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/marketing-control-service/src/main/java/com/acme/marketing/control/application/ControlApplicationService.java#L218)、[JourneyRuntime.java:18](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/runtime-spi/journey-runtime-spi/src/main/java/com/acme/marketing/journey/JourneyRuntime.java#L18)、[V10__referral_award_preparation.sql:1](../../services/benefit-funding-service/src/main/resources/db/migration/V10__referral_award_preparation.sql#L1)。

## 领域建模评价

**INFERRED**：属于混合建模。资金/定价/旅程有真实行为和不变量；不少用例用 record DTO 与应用服务编排，不能宣称全仓富领域模型。BenefitFundingService 超过千行，集中多种资源及结算逻辑，后续适合按预占结算、对账、目录等真实变化点分解，但需要先以资金行为测试保护，不能机械拆接口。

## 任务、命令与渠道状态分开

**CONFIRMED**：Journey 副作用意图创建为 PENDING，dispatch outbox 负责投递；EngagementService.handleJourneyCommand 只有完成处理才 markCommandSucceeded，异常 markCommandRetry 为 RETRY_PENDING。ContactState 又有 PENDING/RETRY_PENDING/ACCEPTED/SENT/DELIVERED/FAILED/CLICKED/UNSUBSCRIBED。因此“命令成功受理”“渠道发送”“用户收到/点击”不是同一状态机，不应只用 SUCCESS 展示所有层级。

证据：[EngagementService.handleJourneyCommand](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/engagement-service/src/main/java/com/acme/marketing/engagement/application/EngagementService.java#L147)、[EngagementMapper 的命令状态更新](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/engagement-service/src/main/resources/mapper/EngagementMapper.xml#L156)。这些状态未在本轮真实渠道验收。
