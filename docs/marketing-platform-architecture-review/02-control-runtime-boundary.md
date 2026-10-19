# 02 控制面与运行面边界评审

## 2026-10-19 更新

设计/运行隔离模型不变；支持子集与旧制品激活门禁已加固，存量制品处置仍需正常发布流程。

当前源码核对基线 `6b9092f`；[完整状态与证据](../marketing-platform-analysis/CURRENT_STATUS.md)。以下保留原评审基线的发现、严重度和测试结果；其中“当前/待实施”均指原评审当时。历史源码链接已固定到原提交，后续实现请从最新状态索引进入。

## 历史报告正文（1392ca9 基线）

评审日期：2026-09-22；源码基线：`1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c` 加现有工作区。模式：`READ_ONLY_ARCHITECTURE_REVIEW`。

`CONFIRMED` 为直接源码或明确测试证据，`INFERRED` 为推断和建议，`UNKNOWN` 为待补证据。所有目标模型、任务与验收均为规划，不表示已经实现或授权执行。

## Separation Assessment

|检查项|Current State / Evidence|Risk|Recommended Boundary|Migration Difficulty|
|---|---|---|---|---|
|运行是否直接读设计JSON|CONFIRMED：Offer 安装已签名政策；Journey 读取已激活计划|语义错误仍能被签名|设计模型只交编译器，runtime 只接ABI制品|低，保护现状|
|运行是否读运营库|CONFIRMED：Flink 直接 JDBC 读 Journey runtime表，并非 mk_definition_version|表结构/可用性耦合；热路径连接/查询阻塞|明确 runtime store Owner与只读契约；测量后选缓存/端口|中，不盲目换RPC|
|运行是否知道前端Schema|未见 React布局进入执行；config仍是字符串map|未知字段/缺配置延迟到运行失败|编译边界将 config 降为 typed spec|中，需旧ABI共存|
|是否每次解析JSON|输入事件逐次解码；计划cache miss解码，非每次读草稿|大payload/冷启动成本|事件有界；计划按digest缓存，安装时验证|中|
|改草稿影响既有实例|CONFIRMED：版本追加，实例pinned；成员/kill可变|“全部输入不可变”是错误承诺|冻结程序；动态事实注明来源/版本/时效|中，需业务接受|
|共享Entity|GraphDefinition 与 JourneyPlan分开，公开record包含map|类型边界部分薄弱，不是全仓共享ORM实体|契约DTO可共享；持久化entity不出Owner|低到中|
|混合表体系|Journey服务同时安装、投影、分发|不同数据生命周期被误删/误恢复|同库不同职责清单/权限/保留|中，无需先拆库|

证据：[RuntimeManifestRegistry.java:21](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/offer-decision-service/src/main/java/com/acme/marketing/decision/runtime/RuntimeManifestRegistry.java#L21)、[JdbcJourneyPlanResolver.java:152](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/jobs/journey-runtime-flink-job/src/main/java/com/acme/marketing/jobs/journey/JdbcJourneyPlanResolver.java#L152)、[GraphDefinition.java:1](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/runtime-spi/lowcode-language-core/src/main/java/com/acme/marketing/lowcode/model/GraphDefinition.java#L1)、[JourneyPlan.java:1](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/runtime-spi/journey-runtime-spi/src/main/java/com/acme/marketing/journey/JourneyPlan.java#L1)。

## A04：运行库访问成为运行可用性依赖

- Problem：Flink resolver 使用同步 DriverManager；新 START 查当前指针，kill 每租户短缓存过期后查库。
- Evidence：CONFIRMED，[JdbcJourneyPlanResolver.java:105](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/jobs/journey-runtime-flink-job/src/main/java/com/acme/marketing/jobs/journey/JdbcJourneyPlanResolver.java#L105)、[JdbcJourneyPlanResolver.java:131](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/jobs/journey-runtime-flink-job/src/main/java/com/acme/marketing/jobs/journey/JdbcJourneyPlanResolver.java#L131)。
- Impact：INFERRED，DB慢/主切会转化为吞吐下降、DLQ或timer重试；不同异常分支必须分别验证。
- Root Cause：安装store与流运行共同承担计划与安全开关权威，连接边界未完全资源隔离；不是误读草稿。
- Current Workaround：128计划缓存，1024 kill缓存，1秒新鲜度；失败不延长未知kill状态。
- Recommended Direction：先确定连接/查询总预算与故障结果，测量阻塞；将SQL适配封装成独立read port，稳定只读schema；如改异步需保护每实例顺序。
- Trade-off：较长缓存降低DB负载却扩大停用窗口；RPC只会把SQL耦合换成网络耦合，需实测再选。
- Priority：P1（严格停用/可用性需求），性能严重度待测；T08。

## 控制面不可用实验

目标（规划）：已安装且有效的Decision继续；新发布暂停。Journey能否继续取决于runtime store和kill读取，而非Control HTTP是否存活；分别注入Control HTTP断连和Journey DB断连。记录决策拒绝率、kill生效延迟、timer迟到和DLQ。不得以一个服务DOWN试验概括整个平面隔离。

## 继承证据

- [上一阶段 04-activity-engine](../marketing-platform-analysis/04-activity-engine.md)：作为发现输入；本章新增判断以定向源码复核为准。
- [上一阶段 09-publish-version-rollback](../marketing-platform-analysis/09-publish-version-rollback.md)：作为发现输入；本章新增判断以定向源码复核为准。
- [上一阶段 10-runtime-architecture](../marketing-platform-analysis/10-runtime-architecture.md)：作为发现输入；本章新增判断以定向源码复核为准。
