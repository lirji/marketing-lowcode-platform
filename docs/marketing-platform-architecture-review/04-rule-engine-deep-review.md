# 04 Rule 引擎、Context 与技术选择复审

## 2026-10-19 更新

支持子集内候选构建和 PricingEngine 已复用，比例别名冲突拒绝；任意条件图、通用 IR 与候选引擎切换没有因此完成。

当前源码核对基线 `6b9092f`；[完整状态与证据](../marketing-platform-analysis/CURRENT_STATUS.md)。以下保留原评审基线的发现、严重度和测试结果；其中“当前/待实施”均指原评审当时。历史源码链接已固定到原提交，后续实现请从最新状态索引进入。

## 历史报告正文（1392ca9 基线）

评审日期：2026-09-22；源码基线：`1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c` 加现有工作区。模式：`READ_ONLY_ARCHITECTURE_REVIEW`。

`CONFIRMED` 为直接源码或明确测试证据，`INFERRED` 为推断和建议，`UNKNOWN` 为待补证据。所有目标模型、任务与验收均为规划，不表示已经实现或授权执行。

## 是不是Engine

CONFIRMED：定价选择/分摊是可复用领域执行内核；Offer编译→制品→安装→求值形成引擎路径，但“图形语义完整引擎化”尚未完成。DRL/DMN是另一条有真实编译/求值的适配器路径，不能拿它们证明Offer主链正确。没有必要为每个业务领域强制套统一任意AST。

|层|已有实现|应补能力|
|---|---|---|
|Definition|GraphDefinition、节点版本、SegmentRule|字段/操作符/类型与支持子集合同|
|Parser/AST|JSON反序列化，图节点和边；不是统一表达式AST|只对实际组合表达式引入typed predicate tree|
|Compile|OfferPolicyGraphCompiler、JourneyCompiler、DRL/DMN|不可忽略语义的lowering、source map|
|Runtime|PricingEngine、Audience compare、Journey条件|同一语义在仿真和在线复用|
|Repository|定义/制品/安装store|继续分权，不造全局RuleRepository|
|Registry|节点目录与vendor SPI|算子类型矩阵，编译与运行版本绑定|

[RuleCompilerService.java:1](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/rule-compiler-worker/src/main/java/com/acme/marketing/compiler/application/RuleCompilerService.java#L1)、[OfferPolicyGraphCompiler.java:1](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/rule-compiler-worker/src/main/java/com/acme/marketing/compiler/application/OfferPolicyGraphCompiler.java#L1)、[DecisionApplicationService.java:56](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/offer-decision-service/src/main/java/com/acme/marketing/decision/application/DecisionApplicationService.java#L56)。

## A01：已签名政策不能保持审批图语义

- Problem：Offer编译忽略条件和边，比例参数与仿真不同。
- Evidence：CONFIRMED，[OfferPolicyGraphCompiler.java:28](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/rule-compiler-worker/src/main/java/com/acme/marketing/compiler/application/OfferPolicyGraphCompiler.java#L28) 与 [GraphSimulationService.java:48](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/marketing-control-service/src/main/java/com/acme/marketing/control/application/GraphSimulationService.java#L48)；编译 `discountBasisPoints`，仿真 `basisPoints`。
- Impact：INFERRED，非VIP可能获得只为VIP审批的优惠；真实资金损失、受影响线上制品数量 UNKNOWN。
- Root Cause：两个可执行语义定义独立演进，签名/审批验证的是身份和流程，没有验证降级等价性。
- Current Workaround：独立policy资格过滤提供时间/商品/人群约束，但不能代替被丢弃的任意图条件；仓库未确认通用自动拦截。
- Recommended Direction：T01先拒绝无法忠实编译的图并治理比例字段，盘点已安装制品；T02再共享IR/evaluator与source map。
- Trade-off：短期收窄配置范围；旧制品不能静默重编译，需新定义/制品版本与审批。
- Priority：**P0（按本轮“可能导致错误优惠/资损”的预防性口径）**。上一轮R01为P1；是分级口径变化，不是新发现生产事故。

## Rule Context治理与领域边界

CONFIRMED：DRL RuleFacts为Map；Journey variables和Audience attributes为字符串map；Offer主链的Cart/Policy较类型化。[RuleFacts.java:1](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/runtime-spi/decision-runtime-spi/src/main/java/com/acme/marketing/decision/rule/RuleFacts.java#L1)、[JourneyRuntime.java:26](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/runtime-spi/journey-runtime-spi/src/main/java/com/acme/marketing/journey/JourneyRuntime.java#L26)、[AudienceService.java:77](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/audience-service/src/main/java/com/acme/marketing/audience/application/AudienceService.java#L77)。

INFERRED方案：编译阶段输出 FactRequirement(fieldId,type,source,maxAge,allowedUse,missingPolicy)，请求入口用ContextResolver一次有界解析，生成不可变FactSnapshot(values,versions,asOf)。Evaluator只读快照，不允许每个operator远程查询。先支持购物车和已存在membership，不凭空接会员属性。租户/权限由受信上下文提供，不能由运营规则改写。

适合规则：准入谓词、优惠组合参数、可审计阈值。保留Domain Policy：金额精度、资源守恒、幂等冲突、合法状态迁移、权限、租户过滤、UNKNOWN恢复。不能让DRL action直接扣预算或用“通用规则”覆盖风控权威。

## Operator Extension Cost

以Audience新增BETWEEN为具体例：确认至少2个生产文件包含多个独立修改点——AudienceService的Operator/validateRule/validateValue/compare/Condition形状，AudienceBuilderPage的UI_OPERATORS、TO_API/TO_UI、输入控件与样本构造；还需相关API合同与测试的精确影响分析。不是确认“只改2文件”，也不是任意新增一行注册即可。[AudienceService.java:221](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/audience-service/src/main/java/com/acme/marketing/audience/application/AudienceService.java#L221)、[AudienceBuilderPage.tsx:14](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/frontend/apps/console/src/features/designers/AudienceBuilderPage.tsx#L14)。

建议 OperatorDescriptor(code,version,acceptedTypes,arity,valueSchema,purity,cost) + 编译时绑定Operator，先以进程内registry管理。运行任意第三方class/脚本热加载不在范围。验收：新增纯operator不改核心分派，类型矩阵和schema自动一致；当只有现有7算子时可先表驱动，不强制框架。

## Build / Integrate / Buy 与替换条件

本轮仅能力复审，未验证最新依赖与本仓Boot/Java兼容，不作升级决定。

|候选|官方能力依据|本仓判断（INFERRED）|
|---|---|---|
|保留定价内核 Build|现有PricingEngine/Allocator|保留金额语义和确定性，修编译前端；成本最低|
|Drools Integrate|KIE支持有状态/无状态会话、事实与规则执行|已有adapter；确需多事实推理时复用，不能自动解决图转换错漏|
|Easy Rules Integrate|官方README标记maintenance mode|简单组合不能证明有替换收益；不新增依赖|
|QLExpress Integrate|Java动态表达式/语言|仅在受控表达式需求明确时PoC，需类型/资源/安全治理|
|Aviator Integrate|JVM脚本语言|同样不提供本仓Release和账本语义；不默认替换|
|Buy|商业决策管理产品|只有规则运营治理需求和预算/合同清晰才进入采购比较；价格与合规未知|

来源（2026-09-22读取）：[Drools官方](https://kie.apache.org/docs/10.0.x/drools/drools/rule-engine/index.html)、[Easy Rules官方](https://github.com/j-easy/easy-rules)、[QLExpress官方](https://github.com/alibaba/QLExpress)、[Aviator官方](https://github.com/killme2008/aviatorscript)。这些是能力依据，不是本仓版本兼容结论。

继续自研的理由是现有范围为受限优惠计算而非通用推理；替换触发器是已批准需求持续超出当前表达模型，维护与验证成本有记录，并且候选对同一黄金集、隔离和预算证明净收益。不能仅因“规则多”替换。双跑仅比较纯计算，不产生双份业务副作用。

## 继承证据

- [上一阶段 05-rule-engine](../marketing-platform-analysis/05-rule-engine.md)：作为发现输入；本章新增判断以定向源码复核为准。
- [上一阶段 15-architecture-risks](../marketing-platform-analysis/15-architecture-risks.md)：作为发现输入；本章新增判断以定向源码复核为准。
