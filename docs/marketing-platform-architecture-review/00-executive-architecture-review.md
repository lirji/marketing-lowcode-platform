# 00 Marketing Low-Code Platform Architecture Review Report

## 2026-10-19 更新

ARCHITECTURE_AT_RISK 是原评审结论；A01/A02/A06/A09 和 A10 的已确认代码缺口已有修复，但未重新签发生产就绪结论。

当前源码核对基线 `6b9092f`；[完整状态与证据](../marketing-platform-analysis/CURRENT_STATUS.md)。以下保留原评审基线的发现、严重度和测试结果；其中“当前/待实施”均指原评审当时。历史源码链接已固定到原提交，后续实现请从最新状态索引进入。

## 历史报告正文（1392ca9 基线）

评审日期：2026-09-22；源码基线：`1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c` 加现有工作区。模式：`READ_ONLY_ARCHITECTURE_REVIEW`。

`CONFIRMED` 为直接源码或明确测试证据，`INFERRED` 为推断和建议，`UNKNOWN` 为待补证据。所有目标模型、任务与验收均为规划，不表示已经实现或授权执行。

## 核心判断

**Final Status: ARCHITECTURE_AT_RISK**。系统已形成真实的营销低代码平台主体：版本化设计、编译制品、签名发布、固定版本运行及分段幂等均有实现。当前优先问题是“批准的设计是否等价于真实执行”，不是缺少更多微服务或中间件。

本阶段在前轮21份报告基础上完成23份架构评审文档、目标架构、13个候选任务/6个阶段、18条Fitness和32个面试问题。重要结论区分CONFIRMED/INFERRED/UNKNOWN；没有把规划写成实现。运行规模、外部合同、整栈恢复与生产影响仍缺证据。

## Executive Review：15个问题

|#|问题|结论|
|---|---|---|
|1|最核心架构是什么？|CONFIRMED：设计控制面→签名编译制品→分slot发布→Decision同步执行/Flink Journey有状态执行→持久副作用命令；不是单一全链串行管道。|
|2|算真正营销低代码平台吗？|INFERRED：是领域专用平台主体，画布有后端合同、编译和运行；尚非任意玩法免开发的成熟通用平台。|
|3|Activity/Rule/Audience/Journey边界合理吗？|总体合理：目标归属、单次决策、群体资格、时序动作不同；不足在Campaign状态、人群身份和跨层语义合同。|
|4|Rule完成引擎化吗？|部分完成：定价内核/编译/安装真实存在；图语义同源性未完成，DRL adapter不能替代这个缺口。|
|5|Journey形成Runtime吗？|CONFIRMED：纯状态转移+Flink state/timer+投影/dispatch，实例固定版本；不等于并行BPMN，缺分支校验已用探针复现。|
|6|Design与Runtime隔离吗？|程序模型基本隔离，不读最新草稿；Flink依赖Journey runtime库，外部事实/成员动态；不能称完全离线自治。|
|7|版本体系足以线上运行吗？|有合理多轴版本与pinned机制；历史留存、旧Schema编辑保真与恢复兼容仍需验收。|
|8|发布安全吗？|有审批/签名/ACK/ABI/锁/CAS/Outbox；这些不覆盖A01业务语义错误；未做当前生产安全认证。|
|9|Rollback语义完整吗？|运行指针回退明确；配置、实例迁移、业务补偿必须分开，不能承诺一个按钮撤销全部效果。|
|10|最大5个风险？|A01优惠语义；A02图编译完整性；A06/A07资格类型与身份；A03/A04长时/故障边界；A08/A11版本留存及外部终态证据。|
|11|最值得保留5个设计？|纯SPI与领域模型；设计/制品分离；generation/activationSequence分离；Journey状态与effect分离；资金数据库约束/账本/稳定幂等。|
|12|优先改造5件？|Offer失败关闭及同源语义；Journey编译完整性；Audience类型/身份；Campaign业务与发布状态；运行解释/恢复与寿命预算。工程门禁并行修复。|
|13|未来哪些值得拆？|已有Decision/worker/Flink/Reward部署先保留；Audience compute/Action worker有条件隔离；Rule纯内核留库，Activity/Release先留Control模块；无负载/Owner证据不再拆。|
|14|如何统一中台？|统一版本/metadata/发布信任/关联/恢复合同，保持订单、画像、权益、资金和渠道Owner；通过组合扩玩法而非万能Map/Runtime服务。|
|15|重新设计保留/改变什么？|保留上面5项；改变独立仿真语义、字符串弱类型、发布前语义漏检和分散解释；渐进兼容，不重写全部。|

问题细节与源码：规则[04](04-rule-engine-deep-review.md)、Journey[06](06-journey-engine-deep-review.md)、版本[07](07-release-version-rollback-review.md)、最终风险[22](22-final-recommendation.md)。

## 本阶段新增/深化证据

- CONFIRMED：临时纯Java探针用当前源码编译，构造trigger→condition、仅true→end。GraphValidator返回`[]`，JourneyCompiler成功，普通START运行报`missing route false from node c`。源码见[GraphValidator.java:48](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/runtime-spi/lowcode-language-core/src/main/java/com/acme/marketing/lowcode/validation/GraphValidator.java#L48)、[JourneyCompiler.java:13](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/runtime-spi/journey-runtime-spi/src/main/java/com/acme/marketing/journey/JourneyCompiler.java#L13)、[JourneyNode.java:17](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/runtime-spi/journey-runtime-spi/src/main/java/com/acme/marketing/journey/JourneyNode.java#L17)。没有数据库/网络副作用，也不是生产事故复现。
- CONFIRMED：画布往返保存固定1.0.0并重建空variables，未来Schema升级与旧图编辑存在兼容前置；[graph.ts:35](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/frontend/apps/console/src/features/designers/graph.ts#L35)、[graph.ts:54](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/frontend/apps/console/src/features/designers/graph.ts#L54)。
- CONFIRMED：老Journey实例按历史激活时间验证签名，当前START才查当前指针；[JdbcJourneyPlanResolver.java:87](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/jobs/journey-runtime-flink-job/src/main/java/com/acme/marketing/jobs/journey/JdbcJourneyPlanResolver.java#L87)。因此“没有版本隔离”不是正确批评。
- CONFIRMED：现有SQL架构检查只覆盖services/platform-web，不覆盖jobs；[ArchitectureRulesTest.java:39](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/architecture-tests/src/test/java/com/acme/marketing/architecture/ArchitectureRulesTest.java#L39)。不能把静态规则存在当全域验证通过。

A01按本轮提示词“可能导致错误活动/资损”口径列预防性P0；上一阶段P1并不代表事实相反。实际受影响制品、用户和金额UNKNOWN，未执行停用或补偿。所有严重度与触发范围见22章。

## Marketing Low-Code Platform Architecture Review Report

|维度|评审结果|
|---|---|
|Business Model|领域营销平台主体明确；外部权威保持边界|
|Domain Boundary|职责总体合理，部分合同/Owner需补证|
|Control Plane|版本/审批/编译存在；活动聚合状态需治理|
|Runtime Plane|真实安装和执行；runtime DB/动态事实依赖需预算|
|Activity Architecture|容器/聚合，不应膨胀为资金God Object|
|Rule Engine|语义不一致为首要风险|
|Audience Engine|定义/样本/投影真实；全量compute与ID映射缺证|
|Journey Engine|单游标状态机Runtime；缺路由静态门禁待修|
|Release Engine|已存在，完善而非重建|
|Version Isolation|程序pinned已实现；历史留存/Schema兼容待验证|
|Rollback|指针明确，实例和补偿独立|
|Runtime Execution|同步报价、异步旅程/动作两条主链|
|Low-Code Model|部分metadata驱动，有真实编译链|
|Metadata|目录/schema引用存在；往返/动态表单/迁移不完整|
|Plugin Architecture|受信进程内扩展优先，热插件不推荐|
|Consistency|本地原子+跨系统收敛，不宣称全局原子|
|Idempotency|多层业务键/fence保留，外部协议需验收|
|Observability|日志/指标/状态存在；运行环境采集UNKNOWN|
|Explainability|分散原因码；同源语义与因果视图待补|
|Failure Recovery|恢复机制存在；整栈/长时/灾备实测不足|
|Scalability|模型已建立；数值目标/benchmark UNKNOWN|
|Service Extraction|定性scorecard完成；无抽取授权或HIGH就绪结论|
|Architecture Fitness|18条可验证合同；未伪装全部已执行|
|Evolution Roadmap|13任务、依赖DAG、6阶段exit/rollback与阻塞|
|Evidence Coverage|复用前轮索引，定向源码/探针/官方候选资料；非全仓全量审计|

**Final Status: ARCHITECTURE_AT_RISK**

## 交付导航

现状与证据看[01](01-current-architecture.md)，模块化边界看[16](16-domain-boundary-review.md)，拆分就绪看[17](17-service-extraction-readiness.md)，目标看[18](18-target-architecture.md)，下一阶段执行输入看[19](19-evolution-roadmap.md)，验收看[20](20-architecture-fitness-functions.md)，面试看[21](21-interview-architecture-assets.md)，完整风险/缺口/70节覆盖看[22](22-final-recommendation.md)。

评审完成不表示实施已授权。保留所有用户既有改动；本阶段只新增报告并更新根进度，未修改产品/数据库/配置/依赖，未Git提交/Push。完整测试结果仍以此前范围为准：前端83、限定后端130与前端构建通过，Lint7警告失败；本轮只新增纯内存反例探针和文档验证。

**ARCHITECTURE REVIEW COMPLETE**。

## 继承证据

- [上一阶段 00-executive-summary](../marketing-platform-analysis/00-executive-summary.md)：作为发现输入；本章新增判断以定向源码复核为准。
- [上一阶段 01-project-landscape](../marketing-platform-analysis/01-project-landscape.md)：作为发现输入；本章新增判断以定向源码复核为准。
- [上一阶段 15-architecture-risks](../marketing-platform-analysis/15-architecture-risks.md)：作为发现输入；本章新增判断以定向源码复核为准。

## 产物验证记录

23个文件名称与提示词一致；231处相对文件引用及行号检查通过，40个本阶段自动索引源码文件指纹未漂移；既有被跟踪/未跟踪任务文件及上一阶段分析报告指纹保持不变。9个Mermaid图做围栏/类型基本结构检查，未渲染；不声明视觉校验已通过。纯内存Journey探针负例失败点与正例COMPLETED符合预期。没有新增产品测试或运行真实业务。
