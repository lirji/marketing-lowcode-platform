# 17 Service Extraction Scorecard 与抽取条件

## 2026-10-19 更新

没有新增量化服务抽取证据或新的拆分决策；本章仍是定性候选评分，不能以本次同步认定可立即拆分。

当前源码核对基线 `6b9092f`；[完整状态与证据](../marketing-platform-analysis/CURRENT_STATUS.md)。以下保留原评审基线的发现、严重度和测试结果；其中“当前/待实施”均指原评审当时。历史源码链接已固定到原提交，后续实现请从最新状态索引进入。

## 历史报告正文（1392ca9 基线）

评审日期：2026-09-22；源码基线：`1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c` 加现有工作区。模式：`READ_ONLY_ARCHITECTURE_REVIEW`。

`CONFIRMED` 为直接源码或明确测试证据，`INFERRED` 为推断和建议，`UNKNOWN` 为待补证据。所有目标模型、任务与验收均为规划，不表示已经实现或授权执行。

## 评分口径与限制

本表是证据化**定性scorecard**，不是已运行的architecture-decomposition-assessment机器评分。该Skill的可回放上游Decision和机器产物当前未生成，本轮也不声称其通过。用户要求的领域/维度全部列出，但不以臆造数字填“分数”。UNKNOWN不计0，不参与平均；价值与就绪度分开。若下一阶段要真实拆服务，必须补指标与Owner后执行正式评估/规划门禁。

已独立部署的领域评估的是继续细拆/自治度，不能再把它写成待从单体抽出。所有结论为INFERRED，证据来自01/11/16与下面源码。

|领域|Domain Boundary|Data Ownership|Transaction Boundary|Load Profile|Failure Isolation|Independent Scaling|Migration Cost|Extraction Readiness|
|---|---|---|---|---|---|---|---|---|
|Rule|定义/编译/求值职责可分，语义有债|compiler制品owner明确|纯计算易分，发布关联需保持|UNKNOWN，组合CPU待测|编译已独立；在线依赖Decision|compiler已可独立；remote求值无证据收益|高：新RPC、事实、缓存、失败语义|LOW正确性阻塞；KEEP库与现有worker|
|Audience|定义/成员与全量compute边界部分清楚|snapshot/segment映射UNKNOWN|本地版本/成员更新可局部事务|UNKNOWN，M/u/r待测|已有service+job|已有独立部署，分片待证明|中高：全量/增量追平、ID、撤销|UNKNOWN，先补合同；不再细拆|
|Journey|纯状态机/安装/投影/动作明确|Flink权威与MySQL投影分开|状态→Kafka→投影分段已设计|UNKNOWN，活跃N与timer密度|已有Flink与服务隔离|作业可单独配置，恢复扩容未验收|高：序列化、在途状态、分区身份|LOW路由/恢复门禁；保留现状治理|
|Runtime|不是单一领域|多Owner不能合并|各自不变量不同|UNKNOWN且负载异质|分引擎已有隔离|Decision/Flink分开更合适|高：统一会增加耦合|NOT_APPLICABLE统一服务；逐引擎评估|
|Release|Control内逻辑边界明确|与审批/审计/slot同库|stage/activate局部原子紧密|UNKNOWN，发布频率未知|当前Control故障影响发布|无独立扩容价值证据|高：拆审批/slot一致性/信任|KEEP_AS_MODULE，抽取证据不足|
|Reward|已有Benefit/Funding领域服务|账本资源与意图Owner明确|预占/账本强事务不可随意切开|UNKNOWN，热点与渠道配额待测|已有外部适配/异步relay|现有服务可独立，不代表热点行可横向消除|高：资损/未知结果/对账|不细拆核心账本；外部合同待补证|

证据：[OfferPolicyGraphCompiler.java:1](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/rule-compiler-worker/src/main/java/com/acme/marketing/compiler/application/OfferPolicyGraphCompiler.java#L1)、[JourneyOutputProjector.java:1](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/journey-service/src/main/java/com/acme/marketing/journeyservice/application/JourneyOutputProjector.java#L1)、[ReleaseApplicationService.java:1](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/marketing-control-service/src/main/java/com/acme/marketing/control/application/ReleaseApplicationService.java#L1)、[BenefitFundingService.java:1](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/benefit-funding-service/src/main/java/com/acme/marketing/benefit/application/BenefitFundingService.java#L1)、[AwardIntentService.java:1](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/benefit-funding-service/src/main/java/com/acme/marketing/benefit/application/AwardIntentService.java#L1)。

## 拆分/合并触发器

|候选|条件触发器|所需证据|当前状态|动作|
|---|---|---|---|---|
|Audience compute worker|离线任务实测挤占在线服务且无法用资源预算隔离|负载/慢查询/失败归因|UNKNOWN|先隔离worker，同Owner，后再评估新服务|
|编译worker进一步隔离|不可信规则资源失控/版本升级干扰|超时/内存PoC与隔离要求|UNKNOWN|优先进程资源隔离，不必新业务域|
|Release拆服务|独立团队/发布SLO且Control变更反复互相阻塞|发布记录/事务合同/Owner|NOT_MET证据不足|保留Control模块|
|Action适配worker|渠道延迟和资源配置独立且量化干扰其他动作|队列/配额/延迟与错误|UNKNOWN|先bulkhead/独立消费组，保持业务幂等Owner|
|合并小服务|无独立负载/Owner，运维成本明显高于隔离收益|跨版本发布共变、事故与维护成本|UNKNOWN|不因单人开发猜测就合并|

未来执行前需14维Readiness（领域、模块、数据、Repository、事务、契约、方向、共享表、跨域写、Entity、测试、观测、配置、迁移成本）逐项实证；当前只读SCOPED报告不能声称HIGH。保留现有部署也需要治理，不等于现状完美。

## 继承证据

- [上一阶段 13-extensibility](../marketing-platform-analysis/13-extensibility.md)：作为发现输入；本章新增判断以定向源码复核为准。
- [上一阶段 16-architecture-evolution](../marketing-platform-analysis/16-architecture-evolution.md)：作为发现输入；本章新增判断以定向源码复核为准。
