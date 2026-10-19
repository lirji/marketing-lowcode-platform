# 15 架构风险登记与可执行验收

## 2026-10-19 更新

原 R01/R04/R06 的代码问题已有修复；R08 人群部分已修复；R02/R03/R05/R07/R09/R10 及旅程寿命部分保留。编号与 10 月复审 Rxx 不同。

当前源码核对基线 `6b9092f`；[完整状态与证据](CURRENT_STATUS.md)。以下保留原评审基线的发现、严重度和测试结果；其中“当前/待实施”均指原评审当时。历史源码链接已固定到原提交，后续实现请从最新状态索引进入。

## 历史报告正文（1392ca9 基线）

- 仓库：`marketing-lowcode-platform`；源码基线：`1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c` 加当前未提交工作区。
- 分析日期：2026-09-22；模式：`READ_ONLY_ANALYSIS`。
- `CONFIRMED`＝源码/配置/测试直接证据；`INFERRED`＝据证据推断；`UNKNOWN`＝尚缺证据。代码存在不等于已部署或已通过真实链路验收。
- 本章遵循用户提示词；产品代码、数据库、运行配置、Git 提交与推送均不变。

## 分级与证据规则

P0＝已有证据表明可导致严重广泛事故、应立即停止相关业务；P1＝上线前应解决或明确禁用的正确性/关键能力问题；P2＝有界功能、工程或演进问题。严重度不授权修复。**本轮未确认 P0，不代表不存在 P0**。静态反例未标成生产复现；当前没有替用户修代码。

## 风险总表

|ID|优先级|分类|结论|
|---|---|---|---|
|R01|P1|defect|Offer 仿真/编译/线上丢条件及百分比字段不一致|
|R02|P1（裂变上线）|missing capability|真实 READY、身份/SKU/参与许可接线缺失，发布仍关闭|
|R03|P2|missing capability|Campaign 领域生命周期未连到持久化发布状态|
|R04|P2|defect|Audience 类型校验与 operator 执行不一致|
|R05|P2（DEV）|defect|切换租户复用不含 tenant 的前端查询缓存|
|R06|P2|defect|7 个 Lint 警告令当前零警告门禁失败|
|R07|P2|missing capability|DMN 工作台未接线；重复 Journey 节点类型映射不完整|
|R08|P2|improvement / 容量风险|Journey 去重集合随活跃信号增长，人群本地投影无条目上限|
|R09|P2|improvement / 规范差距|DEMO_MODE 前端业务数据仍写死|
|R10|P2|证据缺口|人群 snapshotId/segmentId 到在线资格键映射未闭环确认|

## R01：预览不能代表线上优惠

**CONFIRMED**：GraphSimulationService 遍历 condition/edges；OfferPolicyGraphCompiler 只提取优惠节点；比例字段 basisPoints vs discountBasisPoints 不同。**INFERRED** 事故：非 VIP 在仿真拒绝但线上获优惠，或审批金额和线上金额不一致。证据：[GraphSimulationService.java:48](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/marketing-control-service/src/main/java/com/acme/marketing/control/application/GraphSimulationService.java#L48)、[OfferPolicyGraphCompiler.java:28](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/rule-compiler-worker/src/main/java/com/acme/marketing/compiler/application/OfferPolicyGraphCompiler.java#L28)、[OfferPolicyGraphCompiler.java:83](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/rule-compiler-worker/src/main/java/com/acme/marketing/compiler/application/OfferPolicyGraphCompiler.java#L83)。

边界：control/compiler/decision/前端合同。最小完整方案：拒绝未支持语义 + 统一字段；随后同一 IR/evaluator 承担仿真和运行。验收：相同 cart/资格输入下正反分支、折扣比例、封顶、互斥产生同一结果；不支持图明确拒绝。规模中大，依赖确定支持的 DSL 子集；不能仅修改文案。

## R02：裂变不能按现有开关直接上线

**CONFIRMED**：stage 拒绝 REFERRAL；ReadinessPort 无生产实现；subject/participation permit 默认不可用。保留拒绝是正确边界，不应当作要删除的 Bug。证据：[ReleaseApplicationService.java:93](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/marketing-control-service/src/main/java/com/acme/marketing/control/application/ReleaseApplicationService.java#L93)、[ReferralRuntimeReadinessPort.java:1](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/referral-service/src/main/java/com/acme/marketing/referral/application/release/ReferralRuntimeReadinessPort.java#L1)、[ReferralConfiguration.java:1](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/referral-service/src/main/java/com/acme/marketing/referral/infrastructure/ReferralConfiguration.java#L1)。

三个子结果：A 权威身份/SKU/范围映射签收；B READY/ACK、激活、健康与参与事务 epoch 栅栏；C 资格→授权→权益终态→取消/对账端到端恢复。每个都需独立验收；跨系统大规模，外部协议是前置依赖。坏场景为绕过门禁后凭测试替身发奖，当前未证实已发生。

## R03：活动状态与发布事实可能脱节

**CONFIRMED**：Campaign 有领域迁移方法，control 保存/审核处理的是 definition；Mapper 没有对应 Campaign 生命周期更新链。**INFERRED**：新活动发布后仍 DRAFT。[Campaign.java:43](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/marketing-control-service/src/main/java/com/acme/marketing/control/domain/Campaign.java#L43)、[ControlApplicationService.java:218](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/marketing-control-service/src/main/java/com/acme/marketing/control/application/ControlApplicationService.java#L218)、[ControlMapper.xml:86](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/marketing-control-service/src/main/resources/mapper/ControlMapper.xml#L86)。

边界 control/console；先决定多方言活动的聚合状态规则，再接事件或事务更新。验收为从创建到发布/暂停的 API 链，状态与实际发布一致。中等，依赖业务定义；不以 seed SQL 冒充解决。

## R04：合法字段元数据不保证合法规则

STRING tier + GT/VIP 在定义时可接受，preview 转 BigDecimal 失败；DECIMAL IN/`1,2` 定义时被按单值拒绝。[AudienceService.java:167](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/audience-service/src/main/java/com/acme/marketing/audience/application/AudienceService.java#L167)、[AudienceService.java:192](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/audience-service/src/main/java/com/acme/marketing/audience/application/AudienceService.java#L192)。

**CONFIRMED** 静态逻辑，未写数据库复现。边界 audience API/字段元数据/前端。修复操作符×字段类型矩阵、结构化集合值和缺失语义；验收每种组合的接受/拒绝、null/missing、数值/时间比较。中等，不需新中间件。

## R05：DEV 租户缓存串显

A 租户查询后 15 秒内退出并以 B 登录，DEV provider 仅 navigate/setTenantId，活动 queryKey 固定，可能直接复用 A 数据。[AuthContext.tsx:26](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/frontend/apps/console/src/shared/auth/AuthContext.tsx#L26)、[CampaignsPage.tsx:41](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/frontend/apps/console/src/features/campaign/CampaignsPage.tsx#L41)]、[main.tsx:17](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/frontend/apps/console/src/main.tsx#L17)。

**CONFIRMED** 代码路径，未浏览器复现；只确认 DEV，不宣称 OIDC/服务端越权。修复 tenant/identity queryKey 和切换时 cancel/clear，验收旧异步响应不污染 B。小到中等，无外部依赖。

## R06：工程门禁真实失败

`pnpm frontend:lint` 实测退出 1，7 个 Fast Refresh 警告；CI 在测试前执行。[package.json:11](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/frontend/apps/console/package.json#L11)、[ci.yml:54](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/.github/workflows/ci.yml#L54)。

拆分组件和公共函数导出，保留门禁；验收零警告及原测试通过。小，无产品业务决策依赖。不将 warning 文本等同运行异常。

## R07：页面/节点枚举与可运行能力错位

DMN 页面仅本地 state；控制面没有闭环编辑保存和测试行链路。重复节点在 GraphValidator 识别 control.bounded-repeat，registry 不注册，compiler 尾段 Type 映射不匹配。[DmnDesignerPage.tsx:13](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/frontend/apps/console/src/features/designers/DmnDesignerPage.tsx#L13)、[GraphValidator.java:187](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/runtime-spi/lowcode-language-core/src/main/java/com/acme/marketing/lowcode/validation/GraphValidator.java#L187)、[JourneyCompiler.java:50](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/runtime-spi/journey-runtime-spi/src/main/java/com/acme/marketing/journey/JourneyCompiler.java#L50)。

拆成两个子任务：DMN 版本持久化+求值（中等，依赖合同）；重复节点目录/编译/运行与 deadline 一致（中等，依赖明确循环语义）。验收合法图可保存恢复执行，非法或无界循环明确拒绝。

## R08：单次有界不等于生命周期有界

**CONFIRMED**：processedSignalIds 追加、stateTtl 按信号续期；人群缓存未设置 maximumSize。**INFERRED**：持续活跃实例与巨大人群会增长内存/checkpoint/冷启动成本。[JourneyRuntime.java:23](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/runtime-spi/journey-runtime-spi/src/main/java/com/acme/marketing/journey/JourneyRuntime.java#L23)、[JourneyJobReducer.java:46](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/jobs/journey-runtime-flink-job/src/main/java/com/acme/marketing/jobs/journey/JourneyJobReducer.java#L46)、[AudienceMembershipProjection.java:8](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/offer-decision-service/src/main/java/com/acme/marketing/decision/runtime/AudienceMembershipProjection.java#L8)。

补业务保留窗口、绝对寿命/事件配额、分片和可恢复去重设计；验收长时压测与重放正确性，不能随意删去重集。中大，依赖容量与重放目标，尚无已发生 OOM 证据。

## R09/R10：数据真实性与映射

R09：DEMO_MODE 走前端数组，改为隔离租户 seed/API；小到中等；验收刷新仍持久。[CampaignsPage.tsx:41](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/frontend/apps/console/src/features/campaign/CampaignsPage.tsx#L41)。

R10：consumer 使用 segmentId 作为投影键，Offer 字段要求 snapshotId；缺失外部映射证据。[AudienceMembershipConsumer.java:38](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/offer-decision-service/src/main/java/com/acme/marketing/decision/runtime/AudienceMembershipConsumer.java#L38)、[OfferPolicyGraphCompiler.java:96](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/rule-compiler-worker/src/main/java/com/acme/marketing/compiler/application/OfferPolicyGraphCompiler.java#L96)。先做真实链路取证，不直接改名；映射确认后实现稳定 ID/版本契约。中等估算，不确定性高。

## 未升级为确认漏洞的怀疑

- Drools 的线程取消是否形成硬超时：需不可中断用例与线程生命周期测试。
- 发布/kill-switch 本地缓存故障窗口：需断连和消息迟到实验，不能仅凭每秒 reconcile 承诺强一致。
- 目录实时全页扫描、Flink 同步 JDBC、审计头热点：是代码可见的潜在瓶颈，未压测，不宣称已有容量事故。
- OIDC 权限绕过、资金重复扣减、租户 SQL 漏过滤：本轮没有足够反例，不为凑风险数而确认。
