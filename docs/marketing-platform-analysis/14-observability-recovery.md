# 14 可解释性、异常恢复与工程验证

## 2026-10-19 更新

旧 lint 失败已修复；新的本地验证和 CI 配置阻塞见最新索引。统一解释、真实外部恢复与容量仍未完整验收。

当前源码核对基线 `6b9092f`；[完整状态与证据](CURRENT_STATUS.md)。以下保留原评审基线的发现、严重度和测试结果；其中“当前/待实施”均指原评审当时。历史源码链接已固定到原提交，后续实现请从最新状态索引进入。

## 历史报告正文（1392ca9 基线）

- 仓库：`marketing-lowcode-platform`；源码基线：`1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c` 加当前未提交工作区。
- 分析日期：2026-09-22；模式：`READ_ONLY_ANALYSIS`。
- `CONFIRMED`＝源码/配置/测试直接证据；`INFERRED`＝据证据推断；`UNKNOWN`＝尚缺证据。代码存在不等于已部署或已通过真实链路验收。
- 本章遵循用户提示词；产品代码、数据库、运行配置、Git 提交与推送均不变。

## 运营问题能否回答

|问题|已有证据|能力边界|
|---|---|---|
|为什么获得/未获得优惠|Decision candidates 的 ELIGIBLE/拒绝码、pricing reasons|不等于完整人群推导或图分支证明|
|哪个规则失败|GraphValidator 的 nodeId/pointer/code、仿真 trace|Offer 仿真与线上不一致，需先修语义|
|Journey 当前节点|EnrollmentView 的 currentNodeId/status/version|STREAM 为异步 MySQL 投影，有延迟|
|节点为什么失败|MAX_STEPS_EXCEEDED、timer exhausted、DLQ/DLT、command 状态|未确认统一运营页面关联所有事件|
|为什么发券/没发券|AwardIntent 状态、risk block、deliveryResult、资源账本|真实下游终态仍需 orderNo 关联与对账|
|谁发布/回滚|actor、审批决定、manifest/directive、审计链|跨 runtime 实际生效延迟还需观测|

[DecisionApplicationService.java:69](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/offer-decision-service/src/main/java/com/acme/marketing/decision/application/DecisionApplicationService.java#L69)、[GraphValidator.java:30](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/runtime-spi/lowcode-language-core/src/main/java/com/acme/marketing/lowcode/validation/GraphValidator.java#L30)、[JourneyApplicationService.java:240](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/journey-service/src/main/java/com/acme/marketing/journeyservice/application/JourneyApplicationService.java#L240)、[AwardIntentService.java:137](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/benefit-funding-service/src/main/java/com/acme/marketing/benefit/application/AwardIntentService.java#L137)。

## 可观测基建

**CONFIRMED**：服务包含 actuator/Prometheus/OTel 配置；控制面审计、measurement request/order trace 和前端错误上报存在。配置的指标出口不证明仪表盘正在采集，也不能用健康 UP 证明发奖完成。审计 hash chain 可帮助检测链内篡改，但未确认外部不可变锚定/合规留存已经落地。

业务解释应将 tenant、campaign/definition、generation、enrollment、command、sourceRequestId、benefitOrderNo 串联。不要把 subjectToken/订单号直接作为无限基数指标标签。[MeasurementService.java:366](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/measurement-service/src/main/java/com/acme/marketing/measurement/application/MeasurementService.java#L366)、[ControlApplicationService.java:387](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/marketing-control-service/src/main/java/com/acme/marketing/control/application/ControlApplicationService.java#L387)。

## 恢复矩阵

|失败|现有行为|人工/待补证据|
|---|---|---|
|事件非法或乱序|quarantine + replay|源数据修复后审计重放|
|Outbox 发布失败|数据库待发、重试、租约/顺序约束|长期积压处置与容量验证|
|Journey timer 异常|有界重试、耗尽事件；kill 时暂停重试|耗尽后重新调度入口/策略尚需明确|
|渠道瞬断/未知|Provider 稳定幂等键、短事务状态、retry pending|必须核验真实渠道幂等与回执|
|奖励未知结果|原 sourceRequestId 重试/查询，保留账本与 fence|不能盲目换 ID 再发|
|Flink 重启|checkpoint 设计与 pinned plan|实际 checkpoint 恢复、兼容升级和长期停机演练 UNKNOWN|
|配置发布错误|新序号指向留存旧代次|已发生效果另行补偿|
|数据库损坏|backup/restore 脚本和校验|恢复环境、RTO/RPO 与跨系统数据点未实测|

[EventIngestionService.java:116](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/event-gateway-service/src/main/java/com/acme/marketing/eventgateway/application/EventIngestionService.java#L116)、[JourneyRuntimeProcessFunction.java:19](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/jobs/journey-runtime-flink-job/src/main/java/com/acme/marketing/jobs/journey/JourneyRuntimeProcessFunction.java#L19)、[restore.sh:10](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/scripts/restore.sh#L10)。

恢复脚本带覆盖确认且包含本地数据库写入；本轮仅阅读，没有执行。它还处理 ClickHouse 备份，但不能据此认定主衡量查询使用 ClickHouse。referral profile 与新增表的完整恢复覆盖需要另外核对，不能沿用旧 R1 脚本的标签作证。

## 测试与证据分层

本对话已跑：前端 83 项、构建通过；Lint 7 警告失败；纯模块/合同 130 项通过。服务 MySQL 集成、Flink 全栈、远程 CI、安全扫描、容量与恢复测试本轮未跑。仓库有 Testcontainers、ArchUnit、全栈 CI 和性能脚本，但“存在脚本”与“当前通过”分开。

[ArchitectureRulesTest.java:34](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/architecture-tests/src/test/java/com/acme/marketing/architecture/ArchitectureRulesTest.java#L34)、[full-stack.yml:1](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/.github/workflows/full-stack.yml#L1)、[run-capacity-suite.sh:1](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/scripts/run-capacity-suite.sh#L1)。

## Explainability 演进

**INFERRED / 建议**：先统一事实主键与状态来源，构建“设计版本→制品→激活→实例→命令→下游终态”诊断视图，显式标注投影滞后/UNKNOWN。以一个未命中优惠、一次 timer 耗尽、一次渠道超时三种失败故事验收；不应仅增加日志条数。
