# 19 十个面试追问及答题边界

## 2026-10-19 更新

回答旧缺陷追问时应说明后续修复、支持子集和剩余边界；不得继续把已修复缺陷描述为当前行为。

当前源码核对基线 `6b9092f`；[完整状态与证据](CURRENT_STATUS.md)。以下保留原评审基线的发现、严重度和测试结果；其中“当前/待实施”均指原评审当时。历史源码链接已固定到原提交，后续实现请从最新状态索引进入。

## 历史报告正文（1392ca9 基线）

- 仓库：`marketing-lowcode-platform`；源码基线：`1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c` 加当前未提交工作区。
- 分析日期：2026-09-22；模式：`READ_ONLY_ANALYSIS`。
- `CONFIRMED`＝源码/配置/测试直接证据；`INFERRED`＝据证据推断；`UNKNOWN`＝尚缺证据。代码存在不等于已部署或已通过真实链路验收。
- 本章遵循用户提示词；产品代码、数据库、运行配置、Git 提交与推送均不变。

## 1. 你凭什么说这是低代码运行平台？

答题主线：不仅有画布，还能形成有版本的图、编译制品和固定版本运行实例。第二层问新增节点改哪些模块，按 13 章列目录/编译/runtime/合同/前端；第三层问是否热插件，回答只有 Java SPI 与代码目录，动态安装未知。证据：[DefaultNodeRegistry.java:1](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/marketing-control-service/src/main/java/com/acme/marketing/control/application/DefaultNodeRegistry.java#L1)、[JourneyCompiler.java:1](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/runtime-spi/journey-runtime-spi/src/main/java/com/acme/marketing/journey/JourneyCompiler.java#L1)。扣分点：把拖拽等同通用工作流引擎。

## 2. 如何保证仿真和线上一致？

当前应如实回答：没有完全保证，已发现 Offer 分支丢失与比例字段差异。第二层给 VIP/普通用户反例；第三层提出同源 IR/evaluator、黄金用例差分与不支持图拒绝，而不是只增加单元覆盖率。证据：[GraphSimulationService.java:48](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/marketing-control-service/src/main/java/com/acme/marketing/control/application/GraphSimulationService.java#L48)、[OfferPolicyGraphCompiler.java:28](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/rule-compiler-worker/src/main/java/com/acme/marketing/compiler/application/OfferPolicyGraphCompiler.java#L28)。

## 3. 回滚为什么不能直接降低 version？

先区分 generation 与 activationSequence。第二层解释“新指令指向旧制品”；第三层说明多副本最终收敛、旧消息拒绝、旧实例仍固定版本。已发券需补偿，不是回滚。证据：[ReleaseApplicationService.java:208](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/marketing-control-service/src/main/java/com/acme/marketing/control/application/ReleaseApplicationService.java#L208)。

## 4. Journey 重启后等待节点如何恢复？

state/timer 由 Flink checkpoint 恢复，固定 PlanReference；第二层解释恢复可能重处理但 effect commandId 稳定；第三层解释 Kafka EOS 不覆盖外部渠道，需下游幂等与对账。现场追问 checkpoint 全损：本轮没有证明灾备能力，应说明需要 savepoint/备份与重放边界。[JourneyRuntimeProcessFunction.java:11](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/jobs/journey-runtime-flink-job/src/main/java/com/acme/marketing/jobs/journey/JourneyRuntimeProcessFunction.java#L11)。

## 5. Journey 支持并行、循环和人工恢复吗？

单 currentNodeId，分支选一路，没有并行 join；REPEAT 在 runtime 有实现但默认图接线不完整。第二层区分 timer retry、消息 retry 和失败实例恢复；第三层讨论新增并行 token 所需状态模型、join 幂等与迁移成本。[JourneyRuntime.java:38](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/runtime-spi/journey-runtime-spi/src/main/java/com/acme/marketing/journey/JourneyRuntime.java#L38)。不要把 enum 存在当端到端可用。

## 6. 大促优惠如何选最优并保持分摊一致？

按 stage、组策略和互斥集合求解；第二层解释 14 可选候选的组合上限及真实成本；第三层解释最大余数、稳定键排序、退款沿原分摊事实。没有吞吐实测不得说“已支撑大促”。[OfferSelector.java:1](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/runtime-spi/decision-runtime-spi/src/main/java/com/acme/marketing/decision/pricing/OfferSelector.java#L1)、[LargestRemainderAllocator.java:1](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/runtime-spi/decision-runtime-spi/src/main/java/com/acme/marketing/decision/pricing/LargestRemainderAllocator.java#L1)。

## 7. MQ exactly-once 为什么不能保证不重复发券？

Flink checkpoint+Kafka sink 只覆盖该边界；第二层描述 DB commit / offset commit 崩溃窗口；第三层描述远端成功本地超时仍需 sourceRequestId 和永久结果查询。[FlinkKafkaJobRunner.java:46](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/jobs/flink-job-support/src/main/java/com/acme/marketing/jobs/support/FlinkKafkaJobRunner.java#L46)、[AwardIntentService.java:86](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/benefit-funding-service/src/main/java/com/acme/marketing/benefit/application/AwardIntentService.java#L86)。

## 8. Redis 丢数据怎么办？

先确认模式：Decision 幂等 REDIS 是选定存储，不是 JDBC 的透明缓存。第二层说明 24h 回放窗口损失后可能重算不同报价，资金仍需 quote/command 约束；第三层讨论 Redis 持久化/复制、幂等保留及切换合同。本轮不假定部署配置或恢复达标。[DecisionRequestExecutor.java:67](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/offer-decision-service/src/main/java/com/acme/marketing/decision/application/DecisionRequestExecutor.java#L67)。

## 9. 人群到底是如何算出来的？

控制面 preview 计算提交样本；流任务比较上游 segmentIds 集合。第二层区分全量圈选与成员投影；第三层指出 snapshotId 与 segmentId 映射、过期/撤销时效、字段 operator 类型矩阵缺口。[AudienceService.java:77](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/audience-service/src/main/java/com/acme/marketing/audience/application/AudienceService.java#L77)、[AudienceMembershipProjection.java:16](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/jobs/audience-flink-job/src/main/java/com/acme/marketing/jobs/audience/AudienceMembershipProjection.java#L16)。

## 10. 如何证明项目可上线？

先陈述实际证据：前端 83 项与纯模块 130 项通过，Lint失败；第二层要求数据库并发、外部身份/权益/渠道、消息故障与恢复验收；第三层给业务 SLO/容量/灾备和灰度中止条件。当前状态是分析 COMPLETE_WITH_GAPS，不是生产 READY。[ci.yml:1](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/.github/workflows/ci.yml#L1)、[restore.sh:1](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/scripts/restore.sh#L1)。

## 面试讲述顺序

先讲一个真实业务链，再讲不变量、最危险失败窗口、代码中的防线、保留的取舍和证据。遇到未知项直接说明范围。最能体现判断力的是“现有机制解决了哪一段、没有解决哪一段”，不是把所有代码包装成自己已经完成的线上业绩。
