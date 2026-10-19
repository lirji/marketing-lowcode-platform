# 17 五个核心技术难点与失败链路

## 2026-10-19 更新

已把跨层语义、路由及故障分类的部分难点转为代码护栏；保留资金和版本设计，不虚构生产规模或收益。

当前源码核对基线 `6b9092f`；[完整状态与证据](CURRENT_STATUS.md)。以下保留原评审基线的发现、严重度和测试结果；其中“当前/待实施”均指原评审当时。历史源码链接已固定到原提交，后续实现请从最新状态索引进入。

## 历史报告正文（1392ca9 基线）

- 仓库：`marketing-lowcode-platform`；源码基线：`1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c` 加当前未提交工作区。
- 分析日期：2026-09-22；模式：`READ_ONLY_ANALYSIS`。
- `CONFIRMED`＝源码/配置/测试直接证据；`INFERRED`＝据证据推断；`UNKNOWN`＝尚缺证据。代码存在不等于已部署或已通过真实链路验收。
- 本章遵循用户提示词；产品代码、数据库、运行配置、Git 提交与推送均不变。

以下“为什么”是基于结构推导的 **INFERRED 设计理由**，并非作者决策历史。实现事实为 CONFIRMED；收益没有量化实测不填写数字。

## 1. 不可信配置如何安全进入在线运行

- **Problem**：运营编辑的图需要成为可追溯、可验证、可回滚的执行计划。
- **Why Hard**：可配置语义、节点版本、业务审核和实际字节必须绑定；只签名 JSON 无法阻止编译丢条件。
- **Current Solution**：stableTypeId/semanticVersion、图校验、语义 hash、定义版本、编译制品签名、runtime ABI/闭包验证。
- **Code Evidence**：[GraphValidator.java:44](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/runtime-spi/lowcode-language-core/src/main/java/com/acme/marketing/lowcode/validation/GraphValidator.java#L44)、[RuleCompilerService.java:141](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/rule-compiler-worker/src/main/java/com/acme/marketing/compiler/application/RuleCompilerService.java#L141)、[RuntimeManifestRegistry.java:69](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/offer-decision-service/src/main/java/com/acme/marketing/decision/runtime/RuntimeManifestRegistry.java#L69)。
- **Trade-off**：扩展节点要维护多个合同；比直接读最新运营 JSON 更复杂，但能隔离未批准变更。
- **Failure Scenario**：正常为图→签名→安装；失败点是 compiler 丢条件；普通 CRUD 会直接执行可变配置；当前设计挡篡改，挡不住同源语义缺失 R01。
- **Possible Improvement**：同一 IR 兼顾仿真/运行，非法降级拒绝，黄金用例差分；兼容窗口保留旧 ABI。

## 2. 回滚旧版本而不被旧消息再次覆盖

- **Problem**：代次从 5 回到 3，同时 Kafka 中可能还有旧激活通知。
- **Why Hard**：只比较 generation 无法区分合法回滚和乱序旧命令；多副本切换有窗口。
- **Current Solution**：generation 表示制品，activationSequence 表示新指令；签名绑定 slot；锁/CAS/Outbox；runtime 持久化和 reconcile。
- **Code Evidence**：[ReleaseApplicationService.java:226](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/marketing-control-service/src/main/java/com/acme/marketing/control/application/ReleaseApplicationService.java#L226)、[RuntimeManifestRegistry.java:96](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/offer-decision-service/src/main/java/com/acme/marketing/decision/runtime/RuntimeManifestRegistry.java#L96)。
- **Trade-off**：保留历史制品与多种序号，操作复杂；替代方案全局事务更强耦合且不能覆盖外部运行状态。
- **Failure Scenario**：控制提交后通知失败→Outbox 后续投递；副本拒绝缺失制品时保留旧状态；并非 API 成功即所有副本完成。
- **Possible Improvement**：显示期望/实际代次差、ACK 时效和跨 slot 协调，补错序/断连/冷启动演练。

## 3. 长时旅程等待和副作用的恢复

- **Problem**：等待数小时后恢复，不能丢计时器或重复发券。
- **Why Hard**：运行状态、计时器、输出命令与外部数据库属于不同提交边界。
- **Current Solution**：Flink 单 key state+timer、pinned plan、checkpoint/事务 Kafka sink；投影 receipt 与 dispatch outbox；确定性 effect commandId。
- **Code Evidence**：[JourneyRuntimeProcessFunction.java:56](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/jobs/journey-runtime-flink-job/src/main/java/com/acme/marketing/jobs/journey/JourneyRuntimeProcessFunction.java#L56)、[JourneyJobReducer.java:53](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/jobs/journey-runtime-flink-job/src/main/java/com/acme/marketing/jobs/journey/JourneyJobReducer.java#L53)、[JourneyOutputProjector.java:67](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/journey-service/src/main/java/com/acme/marketing/journeyservice/application/JourneyOutputProjector.java#L67)。
- **Trade-off**：需要 broker/checkpoint/状态版本运维；比数据库轮询 timer 更难调试，但状态与恢复归一个流处理边界。
- **Failure Scenario**：timer 触发后进程崩溃→checkpoint 恢复可能重处理；下游依 commandId 防重；外部渠道若不幂等仍会重复。
- **Possible Improvement**：长期状态预算、过期/失败的明确恢复协议、重启后计时器和外部终态对照验收。

## 4. 多优惠和多出资方的精确结算

- **Problem**：多个优惠互斥/叠加，分到商品行和出资方后总和必须一致。
- **Why Hard**：局部最优不一定全局最优，舍入残差会造成对账差异，资源并发还涉及 fencing。
- **Current Solution**：按 PriceStage 执行、有限精确子集求解、稳定排序、最大余数分摊；OfferToken 固化报价；预占/确认/退款状态和数据库守恒 CHECK。
- **Code Evidence**：[OfferSelector.java:31](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/runtime-spi/decision-runtime-spi/src/main/java/com/acme/marketing/decision/pricing/OfferSelector.java#L31)、[LargestRemainderAllocator.java:35](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/runtime-spi/decision-runtime-spi/src/main/java/com/acme/marketing/decision/pricing/LargestRemainderAllocator.java#L35)、[V1__benefit_funding.sql:19](../../services/benefit-funding-service/src/main/resources/db/migration/V1__benefit_funding.sql#L19)。
- **Trade-off**：组合能力有上限、分摊策略固定；接受确定性和可对账性优先于任意玩法组合。
- **Failure Scenario**：普通逐规则扣金额会依执行顺序变化或舍入不守恒；当前算法限制候选并稳定排序；业务范围过滤/编译语义仍需端到端验证。
- **Possible Improvement**：按最坏组合和真实购物车分布测性能；金额/退款 property tests 与真实并发资源测试组合。

## 5. 跨系统奖励超时后的未知结果

- **Problem**：风控/权益调用超时，不能判定未发放后再创建一份奖励。
- **Why Hard**：请求响应不等于外部提交；租约接管可能出现旧执行器迟到；资格可能已取消。
- **Current Solution**：sourceRequestId 稳定、durable claim/leaseVersion、短事务 Outbox，CENTER expected fact 同事务；裂变 preparation 显式 CONFIRM_UNKNOWN 与原确认恢复。
- **Code Evidence**：[AwardIntentService.java:95](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/benefit-funding-service/src/main/java/com/acme/marketing/benefit/application/AwardIntentService.java#L95)、[AwardIntentRelayMapper.xml:9](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/benefit-funding-service/src/main/resources/mapper/AwardIntentRelayMapper.xml#L9)、[ReferralAwardPreparation.java:118](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/benefit-funding-service/src/main/java/com/acme/marketing/benefit/domain/ReferralAwardPreparation.java#L118)。
- **Trade-off**：宁可保留 UNKNOWN/HELD 等待核对，也不盲目重发；业务可用性与自动化速度降低。
- **Failure Scenario**：外部成功但响应丢失→同身份恢复；若换 key 则下游无法关联；已有 fence 只限制本地回写，不能替代外部幂等。
- **Possible Improvement**：权威主体/SKU/确认查询合同、终态账本与取消栅栏真实接线；裂变尚未完成这些端到端验收。

## 五个值得保留的架构设计

1. 设计定义、签名制品、运行代次分别建模。
2. 代次和激活序号解耦，使回滚仍保持命令单调。
3. Journey 状态机输出命令，副作用由独立持久化分发执行。
4. 资金以数据库约束、版本/fence 和账本维护完整性。
5. 失去可信依据时保留裂变拒绝门禁，不以内部验证代替真实授权。
