# 16 领域边界与模块化策略

## 2026-10-19 更新

沿用既有服务边界；没有借整改拆服务、改资金事务或合并全体 Runtime。

当前源码核对基线 `6b9092f`；[完整状态与证据](../marketing-platform-analysis/CURRENT_STATUS.md)。以下保留原评审基线的发现、严重度和测试结果；其中“当前/待实施”均指原评审当时。历史源码链接已固定到原提交，后续实现请从最新状态索引进入。

## 历史报告正文（1392ca9 基线）

评审日期：2026-09-22；源码基线：`1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c` 加现有工作区。模式：`READ_ONLY_ARCHITECTURE_REVIEW`。

`CONFIRMED` 为直接源码或明确测试证据，`INFERRED` 为推断和建议，`UNKNOWN` 为待补证据。所有目标模型、任务与验收均为规划，不表示已经实现或授权执行。

## 边界四层不可混用

CONFIRMED：当前是多服务+流作业，不是单进程模块化单体。建议是保留部署基线、强化内部逻辑边界；既不先合并全部服务，也不增加一组Engine微服务。是否将控制面若干服务合并，需团队/发布频率/负载/故障统计证明，当前UNKNOWN。

|概念|真实归属/粒度|业务与数据责任|允许依赖 / 禁止依赖|
|---|---|---|---|
|Activity|Campaign聚合，Control中的领域模块|目标/归属/业务状态|定义与Release用例契约；不直接写资金|
|Rule|跨definition/compiler/runtime的逻辑能力|纯求值、定价；制品由compiler所有|typed facts/合同；不读草稿/任意RPC/改账本|
|Audience|已有服务和投影，是独立资格上下文候选|字段、群体定义、membership身份|权威provider合同；不授予交易权限|
|Journey|已有服务+SPI+Flink的逻辑上下文|计划安装、实例、输出和分发|只生成命令；不调用跨域Mapper|
|Release|Control内部发布模块，各runtime各自安装适配|manifest、activation、ACK与审计|稳定制品合同；不改在途实例状态|
|Runtime|执行责任总称，不是单一业务实体/服务|各引擎按自身负载独立运行|只依赖签名运行模型；不统一成God Runtime|
|Reward|Benefit/Funding已有上下文|资源完整性、意图/终态/对账|外部履约port；不让通用插件改账|

模块公开API优先contracts/port与不可变DTO，内部Mapper/domain持久对象不导出。实际团队Owner为UNKNOWN；上表是责任Owner建议，不虚构组织分工。

## Day-0 Fitness评审（SCOPED）

|不变量|本轮证据与结论|
|---|---|
|Repository和跨域写|应用层ports、MyBatis adapters已有；未做全库ACL/动态访问审计，UNKNOWN全局通过|
|Entity外泄|Graph/Plan/Contract与持久模型分开有证据；不确认全域零泄漏|
|契约协作|contracts、事件、HTTP ports存在；外部真实合同G01仍缺|
|数据Owner|11章核心表已列；runtime store供Flink只读需显式治理|
|事务Owner|资金强事务与发布slot强事务不拆；跨域Outbox终态收敛|
|依赖方向/循环|已有ArchUnit规则；本轮未重跑全模块，不能声明全仓无环|
|部署与领域|已分开描述，不以模块数量计算自治度|
|抽取就绪|缺负载/团队/运行证据，不能得HIGH|

[ArchitectureRulesTest.java:1](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/architecture-tests/src/test/java/com/acme/marketing/architecture/ArchitectureRulesTest.java#L1)、[BenefitFundingService.java:1](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/benefit-funding-service/src/main/java/com/acme/marketing/benefit/application/BenefitFundingService.java#L1)、[JdbcJourneyPlanResolver.java:1](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/jobs/journey-runtime-flink-job/src/main/java/com/acme/marketing/jobs/journey/JdbcJourneyPlanResolver.java#L1)。

## 当前阶段的架构选择

INFERRED：保持已有独立Decision在线进程、Flink作业和副作用服务，保护故障/负载隔离；Control内部Activity/Release/Definition先模块化。Rule Runtime保持库嵌入在线Decision，不为每次报价新增远程规则服务。Audience全量计算未来可成为隔离worker，但当前连数据权威与身份还需补证。

判断微服务收益的门槛：独立SLO/资源曲线、团队Owner、契约兼容和独立数据权限、真实发布干扰或故障传播证据。满足仅触发服务边界评估，不自动执行拆分。未知项不能以“默认微服务最佳实践”补齐。

共享内核仅容纳稳定身份/错误/签名/合同，不放全部业务enum和可变实体。Benefit大服务按用例测试保护拆内部职责，不把同一预占账本事务拆成远程链。实现细分应先做局部影响分析，本报告不批准全仓重构。

## 继承证据

- [上一阶段 12-data-model](../marketing-platform-analysis/12-data-model.md)：作为发现输入；本章新增判断以定向源码复核为准。
- [上一阶段 13-extensibility](../marketing-platform-analysis/13-extensibility.md)：作为发现输入；本章新增判断以定向源码复核为准。
- [上一阶段 16-architecture-evolution](../marketing-platform-analysis/16-architecture-evolution.md)：作为发现输入；本章新增判断以定向源码复核为准。
