# 架构复审与重构必要性判断

## 2026-10-19 复核状态

R01–R06 的代码修复与 R08 门禁扩展已交付并有对应验证；R07 的业务/外部合同和 R09 的内部职责整理仍保留。当前代码基线为 `6b9092f`，不能继续把下面的 9 项都当作当前未修复缺陷，也不能因此声称生产风险归零。

详见[当前状态及风险映射](../../docs/marketing-platform-analysis/CURRENT_STATUS.md)和[整改交付](../../docs/delivery/architecture-correctness/DELIVERY.md)。以下保留 10 月 18 日针对 `1392ca9` 的审查证据；每个风险的首行给出最新处理状态。

## 原始审查记录（1392ca9 基线）

- workspace: `/Users/liruijun/personal/LLM/marketing-lowcode-platform`
- generated_at: 2026-10-18
- source_of_truth: 当前源码；HEAD `1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c`
- 范围：架构审查、无业务副作用验证及建议；未实施重构。
- 证据口径：CONFIRMED 表示源码或本轮探针确认；INFERRED 表示条件推演；UNKNOWN 表示尚无验证证据。
- 本轮复用 2026-09-22 的分析作为定位线索，关键结论已回查源码。既有分析目录、README 和测试数据改动保留。

## 1. 审查范围与能力状态

**结论：需要有针对性的渐进重构，优先统一低代码语义、编译校验和编辑保存模型；现有服务拓扑可以保留。** 当前风险主要来自“通过校验的设计不一定等于实际执行”，以及临时基础设施故障的恢复行为。没有足够证据支持整体重写、继续拆服务或合并服务。

当前是 Maven 多模块微服务仓库，包含 11 个服务模块（含网关、编译 worker 和可选裂变服务）、3 个 Flink 作业、运行时 SPI 及 React 控制台。保留现有 JDK 21 / Spring Boot 4.0.2 基线；本轮未做依赖升级、维护状态或漏洞评估，个人新项目默认技术栈不构成改造既有项目的理由。

|能力|状态与边界|
|---|---|
|定义、审批、编译、签名制品、运行时安装|已实现；Offer/Journey 的语义完整性存在下述缺陷|
|Decision 定价、金额分摊、Offer Token|已实现；定价内核应保留，编译前端需要治理|
|Journey 纯状态机、Flink 状态与定时器|已实现；固定版本与历史激活验签存在，不读取最新草稿替换在途实例|
|资金账户、预占、账本、CAS、Outbox|已实现；有数据库非负/守恒约束，不能把资金原子事务拆成远程调用|
|人群字段、定义、样本预览、成员投影|已实现；样本预览不等于全量人群计算，类型与标识合同仍需补齐|
|Kafka 消费、外部权益投递|可选开关；以实际配置为准，不能因有消费者类就视作默认启用|
|裂变真实发布与完整跨系统履约|部分实现；Control 对 REFERRAL 发布仍明确拒绝，不能把本地 VERIFIED/安装能力称为业务闭环|
|生产容量、真实故障恢复、业务端到端|本轮未验证；不以历史构建或健康检查替代|

值得保留的设计有四项：领域/SPI 与基础设施分离；设计模型和签名运行制品分离；发布 generation 与 activationSequence 分开并用锁/CAS/Outbox 保护；资金完整性由本地事务、版本校验和数据库约束共同承担。证据见第 7 节 E09–E12。

## 2. 十个维度的发现

|维度|本轮结论|
|---|---|
|架构边界|现有边界总体合理。原生架构检查通过；限定扫描未发现服务间显式 Repository/持久对象导入或跨 Owner 字面 SQL 写。尚不能证明全域自治。R08/R09 是内部治理债。|
|数据一致性|主要确定性缺陷是模型语义在编辑、仿真、编译、执行之间丢失：R01–R04。发布可靠投递与签名不能修正错误编译结果。|
|并发|资金 CAS、数据库约束和发布 slot 锁值得保留；不能据静态代码认定所有竞争场景通过。外部 relay 的稳定幂等键/租约回写存在，外部去重和终态查询合同未在本轮验证。|
|可用性|Journey 运行线程同步查 MySQL；数据库异常与坏消息进入相同异常分支，存在 R05。|
|性能|人群投影每副本装载、查询无分页及变更查询索引缺口是 R06；优惠选择已有候选上限，不能直接宣称无界组合爆炸。|
|MQ|Outbox、Flink checkpoint/事务 sink、业务收据存在，保证分段成立；不代表外部数据库/渠道副作用天然 exactly-once。Journey 普通信号和 timer 的异常策略不同。|
|数据库|资金完整性约束、租户联合键、版本更新存在。人群投影按 updated_at 扫描，但仓库迁移未见该查询所需索引；实际数据库可能有额外索引，尚未查询。|
|Redis|Decision 可选择 JDBC 或 Redis 幂等存储；Redis 模式下它承载请求所有权和结果，不是可随意丢弃的缓存。故障时不能透明改用另一套键空间。|
|安全|业务服务 OIDC 模式从验证后的 JWT 构造租户上下文。DEV 模式允许自报身份，权限头缺失时默认 `*`。基础 Compose 默认 DEV 且入口绑定 loopback；secure 叠加与 Helm 使用 OIDC。未证实生产暴露 DEV，不将其写成已确认线上越权。发布门禁仍应拒绝生产使用 DEV。|
|AI 额外风险|所审主链和依赖中未发现 LLM/Agent/RAG 执行路径；提示注入和模型成本不适用，不编造 AI 风险。|

## 3. 风险清单

### [P0] R01：Offer 仿真与编译后执行不等价

> 2026-10-19 状态：代码修复并验证；仍限定支持子集，旧制品需正常重新发布。

- 问题：仿真沿图边执行 `offer.condition`；编译器仅提取 fixed/percentage 节点，不保留条件和边。仿真读取 `basisPoints`，编译读取 `discountBasisPoints`。线上资格过滤只有时间、渠道、商品范围及人群条件，无法恢复被丢掉的任意图条件。
- 触发条件：审批图使用条件分支约束优惠，或比例节点使用仿真支持的字段。
- 影响范围：可能错误发放优惠或不给应有优惠。纯内存实测：非 VIP 的仿真折扣为 0，编译后定价折扣为 1000 分；比例样本仿真为 1000 分，编译后为 0。两个图均通过现有 GraphValidator。未核实线上制品曝光和实际损失；P0 按可能资损定级。
- 源码证据：[OfferPolicyGraphCompiler.java:28](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/rule-compiler-worker/src/main/java/com/acme/marketing/compiler/application/OfferPolicyGraphCompiler.java#L28)、[GraphSimulationService.java:48](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/marketing-control-service/src/main/java/com/acme/marketing/control/application/GraphSimulationService.java#L48)、[DecisionApplicationService.java:87](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/offer-decision-service/src/main/java/com/acme/marketing/decision/application/DecisionApplicationService.java#L87)。本轮探针调用真实仿真、编译器和 PricingEngine；没有启动服务或实际发放权益。
- 修复方案：先拒绝不能忠实编译的图并规范比例字段；随后使预览和线上复用同一纯执行模型/求值器，建立差分样本。已签名旧制品须盘点后按新版本重新审批发布，不能静默改写。
- 改造成本：中；完整统一语义为中高。
- 优先级：第一批，阻断错误新发布。
- 能力状态：已实现缺陷，CONFIRMED；生产影响 UNKNOWN。

### [P1] R02：Journey 必需路由缺失仍可编译成功

> 2026-10-19 状态：代码修复并验证；必需路由与图完整性在编译前检查。

- 问题：GraphValidator 检查现有边是否合法，没有检查运行必需端口是否都有出口；JourneyCompiler 只检查重复路由和唯一 trigger。执行到缺失分支才报错。
- 触发条件：例如 condition 只有 true 路由，收到走 false 的信号；同类问题也应覆盖 wait/send 等必需出口。
- 影响范围：已批准并安装的旅程在特定输入下不能推进，信号可能进入死信路径。
- 源码证据：[GraphValidator.java:48](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/runtime-spi/lowcode-language-core/src/main/java/com/acme/marketing/lowcode/validation/GraphValidator.java#L48)、[JourneyCompiler.java:17](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/runtime-spi/journey-runtime-spi/src/main/java/com/acme/marketing/journey/JourneyCompiler.java#L17)、[JourneyNode.java:14](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/runtime-spi/journey-runtime-spi/src/main/java/com/acme/marketing/journey/JourneyNode.java#L14)。旧报告已有缺 false 路由的纯内存反例；本轮核对源码未变，未重复该探针。
- 修复方案：按节点类型集中声明必需端口、出口基数和终止约束，在发布前校验并由编译器再次拒绝；补全 true/false、wait、repeat 边界样本。
- 改造成本：低至中。
- 优先级：第一批，与 R01 并行治理。
- 能力状态：已实现缺陷；源码 CONFIRMED，历史探针仍适用于当前源码。

### [P1] R03：Audience 字段类型和运算符没有统一执行合同

> 2026-10-19 状态：代码修复并验证；类型/算子治理已落实，身份映射另列 R07。

- 问题：创建规则只验证值能否解析为字段类型，不验证类型/运算符组合；GT/GTE/LT/LTE 一律按 BigDecimal 求值。
- 触发条件：INSTANT 字段配 GT 等比较，或其他未受限的非法类型/运算符组合。
- 影响范围：规则保存成功，预览执行失败。本轮实测 INSTANT + GT 校验接受，比较两个合法时间字符串时抛 `NumberFormatException`。
- 源码证据：[AudienceService.java:159](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/audience-service/src/main/java/com/acme/marketing/audience/application/AudienceService.java#L159)、[AudienceService.java:192](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/audience-service/src/main/java/com/acme/marketing/audience/application/AudienceService.java#L192)、[AudienceBuilderPage.tsx:13](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/frontend/apps/console/src/features/designers/AudienceBuilderPage.tsx#L13)。探针仅用内存字段类型提供者调用真实校验/求值，不证明数据库行为。
- 修复方案：建立类型—运算符矩阵与类型化比较；服务端拒绝不支持组合，前端按同一能力描述提供选项。先覆盖现有算子，无需通用脚本引擎。
- 改造成本：中。
- 优先级：第一批正确性修复。
- 能力状态：已实现缺陷，CONFIRMED。

### [P1] R04：画布往返保存不保真，会破坏有效定义

> 2026-10-19 状态：代码修复并验证；图元数据及具名端口往返保留。

- 问题：读取删除 `offer.end` 节点但保留连线；保存新增 `${definitionId}-end`，不重写旧连线。保存还强制写 1.0.0、空 variables，并重建 annotations。
- 触发条件：加载结束节点 ID 不是上述固定形式的合法图后再保存；或编辑含自定义 variables 的旅程。
- 影响范围：产生悬空边，或丢失 stateTtlSeconds/maxStepsPerSignal 等执行限制。本轮实际函数探针确认 `s → e` 保存后只有 `saved-offer-end`，仍有 `s → e`；旅程变量从 `{stateTtlSeconds:3600,maxStepsPerSignal:20}` 变为 `{}`。JourneyCompiler 会对丢失值采用默认值。
- 源码证据：[graph.ts:24](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/frontend/apps/console/src/features/designers/graph.ts#L24)、[graph.ts:83](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/frontend/apps/console/src/features/designers/graph.ts#L83)、[LowCodeDesigner.tsx:88](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/frontend/apps/console/src/features/designers/LowCodeDesigner.tsx#L88)、[LowCodeDesigner.tsx:129](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/frontend/apps/console/src/features/designers/LowCodeDesigner.tsx#L129)、[JourneyCompiler.java:41](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/runtime-spi/journey-runtime-spi/src/main/java/com/acme/marketing/journey/JourneyCompiler.java#L41)。现有 graph.test 只验证部分节点恢复，没有验证完整后端图往返等价。
- 修复方案：编辑器保存完整原始 GraphDefinition，界面隐藏不等于删除业务节点；局部编辑合并回原模型，保留 ID、变量和版本；版本升级使用明确迁移步骤。
- 改造成本：中。
- 优先级：第一批，与后端正确性护栏一起验收。
- 能力状态：已实现缺陷，CONFIRMED；未来 Schema 升级进一步放大。

### [P1] R05：Journey 将部分临时数据库故障按坏消息处理

> 2026-10-19 状态：代码修复并验证；存储故障交给恢复，真实容量/灾备未整体验收。

- 问题：resolver 在运行线程同步通过 DriverManager 查询 kill switch/当前指针/历史制品；SQLException 包装为 IllegalStateException。普通事件入口捕获一般 RuntimeException 后输出死信并返回；timer 则有限重试，二者恢复语义不同。
- 触发条件：kill switch 缓存的一秒新鲜窗口已过、START 查当前指针或缓存缺失时，MySQL 短暂不可用/主切。
- 影响范围：合法事件可能被送入死信且实例不推进；死信保留了恢复线索，不能称作不可恢复丢失。慢连接/查询还会阻塞算子线程。未执行真实故障注入，影响程度为 INFERRED。
- 源码证据：[JdbcJourneyPlanResolver.java:63](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/jobs/journey-runtime-flink-job/src/main/java/com/acme/marketing/jobs/journey/JdbcJourneyPlanResolver.java#L63)、[JdbcJourneyPlanResolver.java:112](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/jobs/journey-runtime-flink-job/src/main/java/com/acme/marketing/jobs/journey/JdbcJourneyPlanResolver.java#L112)、[JourneyRuntimeProcessFunction.java:35](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/jobs/journey-runtime-flink-job/src/main/java/com/acme/marketing/jobs/journey/JourneyRuntimeProcessFunction.java#L35)、[JourneyRuntimeProcessFunction.java:83](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/jobs/journey-runtime-flink-job/src/main/java/com/acme/marketing/jobs/journey/JourneyRuntimeProcessFunction.java#L83)。本地 resolver 未显式设置查询超时；部署连接 URL 是否另设超时未确认。
- 修复方案：区分无效输入、业务暂停和可重试基础设施错误；暂时错误保留原信号身份，按作业恢复策略重试或持久重试。补连接/查询总预算，保持 kill switch 新鲜度和失败时拒绝执行；不能用无限期旧缓存换可用性。
- 改造成本：中。
- 优先级：接入真实连续业务前处理；与 R02 分开验收。
- 能力状态：已实现异常分类缺口，源码 CONFIRMED；故障演练未执行。

### [P2] R06：人群投影冷启动和变更拉取缺少数据量边界

> 2026-10-19 状态：代码修复并验证；主键回源加有界缓存，高基数容量未实测。

- 问题：每个 Decision 副本从 Instant.EPOCH 拉取所有投影行，查询没有 LIMIT/分页/有效期过滤，先装载再清理过期数据；内存 Map 无容量上限。增量查询按 updated_at 过滤排序，仓库迁移仅有以 tenant/audience/subject 开头的主键，未见 updated_at 索引。
- 触发条件：成员量、历史行数或副本数量增加，多实例同时冷启动，或故障后有大量待追赶变更。
- 影响范围：DB 扫描与传输、Java 结果列表/Map 内存和启动时间同时上升，扩容会放大共享数据库负载。尚无压测，不能给出失效 QPS。
- 源码证据：[AudienceMembershipProjection.java:12](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/offer-decision-service/src/main/java/com/acme/marketing/decision/runtime/AudienceMembershipProjection.java#L12)、[DecisionMapper.xml:214](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/offer-decision-service/src/main/resources/mapper/DecisionMapper.xml#L214)、[V1__decision_runtime.sql:42](../../services/offer-decision-service/src/main/resources/db/migration/V1__decision_runtime.sql#L42)。
- 修复方案：测量行数/字节与查询计划，增加匹配索引和有界批次；使用可验证的稳定游标/水位处理同时间戳与并发提交；明确内存预算、过期清理及冷启动并发。仅分页不能解决多副本全量热集复制。
- 改造成本：中。
- 优先级：扩量/扩容前。
- 能力状态：已实现结构性风险，CONFIRMED；容量影响 INFERRED。

### [P2] R07：Campaign 业务状态与 Definition/Release 状态缺少完整衔接

> 2026-10-19 状态：仍待业务合同；活动聚合状态、人群身份和外部终态未完整闭环。

- 问题：Campaign 定义了 submit/approve/activate/pause 状态机，但当前持久用例主要创建/查询 Campaign；审批改变 Definition/Approval，激活改变 Manifest/slot。本轮未找到 Campaign 状态持久迁移的主链。
- 触发条件：运营需要用“活动 ACTIVE/PAUSED”表示活动整体是否真实运行，尤其一个活动包含多定义、多 runtime 时。
- 影响范围：列表状态与真实运行状态可能不同，不能把一个 Manifest 激活简单等同整个 Campaign 激活。当前是业务模型/用例衔接缺口，未证实导致越权或实际发放事故。
- 源码证据：[Campaign.java:35](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/marketing-control-service/src/main/java/com/acme/marketing/control/domain/Campaign.java#L35)、[ControlApplicationService.java:60](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/marketing-control-service/src/main/java/com/acme/marketing/control/application/ControlApplicationService.java#L60)、[ReleaseApplicationService.java:180](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/marketing-control-service/src/main/java/com/acme/marketing/control/application/ReleaseApplicationService.java#L180)。
- 修复方案：先明确运营意图状态、审批状态与运行观察状态的关系，再补用例/条件更新/审计；跨 runtime 是否要求一起切换需业务确定。Release 先保留 Control 内部模块。
- 改造成本：中。
- 优先级：正确性问题之后，完善运营闭环时。
- 能力状态：规划缺口；现有接线范围 CONFIRMED，业务聚合规则 UNKNOWN。

### [P2] R08：架构门禁通过不代表所有运行边界受保护

> 2026-10-19 状态：门禁扩展并验证；裂变既有 231 项依赖冻结为基线，未清理全部旧债。

- 问题：SQL 护栏只扫 services/platform-web；architecture-tests 依赖未包含 jobs 和 edge-gateway。应用层持久化禁止依赖包列表也未覆盖 referral。当前 Job resolver 的三段内联 SQL 因此不会使现有检查失败。
- 触发条件：新增 Job、网关或裂变代码引入不符合边界的依赖/持久化实现。
- 影响范围：9 项检查全绿仍可能漏掉上述范围及 R01–R04 的跨层语义回归。
- 源码证据：[ArchitectureRulesTest.java:39](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/architecture-tests/src/test/java/com/acme/marketing/architecture/ArchitectureRulesTest.java#L39)、[ArchitectureRulesTest.java:86](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/architecture-tests/src/test/java/com/acme/marketing/architecture/ArchitectureRulesTest.java#L86)、[architecture-tests/pom.xml](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/architecture-tests/pom.xml)、[JdbcJourneyPlanResolver.java:112](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/jobs/journey-runtime-flink-job/src/main/java/com/acme/marketing/jobs/journey/JdbcJourneyPlanResolver.java#L112)。
- 修复方案：按真实边界补扫描和独立 Job 检查，明确共享 runtime 数据的 reader/owner；遵守 SQL 统一归属要求，迁出内联 SQL 时保持运行语义。增加模型差分/往返/路由不变量测试，避免只增加包名检查。
- 改造成本：低至中。
- 优先级：随第一批修复补上，防止问题重现。
- 能力状态：已实现检查覆盖缺口，CONFIRMED。

### [P3] R09：BenefitFundingService 多种用例聚集，后续修改面偏大

> 2026-10-19 状态：后续改进；未重构资金事务或拆分服务。

- 问题：同一应用服务承载权益配置、SKU 校验、资金预占/结算/退款、分桶、过期处理、对账、命令回执、账本和 Outbox。1241 行只是定位线索，真正的债务是不同变化原因共用一组事务/内部辅助路径。
- 触发条件：继续增加权益类型、结算操作或对账策略，需要反复改同一类。
- 影响范围：审查与回归范围扩大；未发现仅因类大导致的已确认资金错误。
- 源码证据：[BenefitFundingService.java:112](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/benefit-funding-service/src/main/java/com/acme/marketing/benefit/application/BenefitFundingService.java#L112)、[BenefitFundingService.java:270](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/benefit-funding-service/src/main/java/com/acme/marketing/benefit/application/BenefitFundingService.java#L270)、[BenefitFundingService.java:439](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/benefit-funding-service/src/main/java/com/acme/marketing/benefit/application/BenefitFundingService.java#L439)、[BenefitFundingService.java:943](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/benefit-funding-service/src/main/java/com/acme/marketing/benefit/application/BenefitFundingService.java#L943)。
- 修复方案：在已有事务/并发测试保护下，按权益配置、结算用例、过期/对账等变化点拆内部协作者；保持资金事务 Owner、加锁顺序、CAS、账本和 Outbox 原子性。
- 改造成本：中。
- 优先级：后续增量开发时逐步整理，晚于 R01–R06。
- 能力状态：已实现结构债，CONFIRMED；维护风险 INFERRED。

另有需要补证的合同：Audience 快照 ID 由 UUID 生成，而流事件使用 segmentId 写入 Decision 投影；报价查询字段称 requiredAudienceSnapshotId，HTTP 又存在直接更新 snapshotId 的入口。两种路径的映射和撤销效果尚未形成可信的完整证据，不能直接断言所有人群匹配失败。裂变身份、权益冻结引用、真实 READY/ACK 与外部终态也仍需原合同与真实接线证明。

## 4. 十倍流量推演

以下按当前热点行、同步 I/O、全量投影和单实例状态结构推演，没有虚构 QPS。无法凭代码确定统一的“第一瓶颈”：大人群/冷启动优先怀疑 R06；MySQL 变慢优先怀疑 R05；高组合报价关注 CPU；慢渠道关注副作用队列。

|资源|条件与可能事故|已有约束和最小补法|
|---|---|---|
|数据库|多副本每秒增量查询、冷启动全表读取；热点资源行/审计头锁竞争|现有资金分桶/CAS保留；先测扫描/锁等待，修 R06 索引与分页，维持短事务和统一加锁顺序|
|Redis|启用 Redis 幂等或网关共享限流后，故障/热点租户影响请求|明确拒绝行为和所有权恢复；禁止无证据切换幂等权威；测热点槽与等待时间|
|MQ|到达率超过消费/渠道处理率，积压越过保留窗|已有 Outbox/事务 sink；监控 oldest age、重放窗、隔离队列，不仅监控条数；不能盲目增分区破坏顺序合同|
|JVM|投影 Map/查询列表复制；Journey processedSignalIds 随在途事件积累|测热集字节、GC、checkpoint 大小；限制热集/实例生命周期与信号量，明确去重保留窗|
|线程池/执行线程|Flink 同步 JDBC 或报价计算占用执行资源；外部慢调用拖慢 relay|先补 I/O 总预算和受控并发；不靠无限线程或更大连接池掩盖下游容量|
|RPC|发布预热/外部事实与权益调用集中，重试放大|保留已有 timeout/熔断；按完整链路预算并发和重试，纯求值器不逐节点远程查事实|
|下游|短信/权益配额先于内部资源饱和|现有 relay 租户批次与稳定幂等键保留；测公平性、恢复速率及成功后丢响应的查询能力|
|网络|人群冷装载、制品分发、Flink checkpoint 同时发生|限制并发冷启动和发布批次，量测带宽/传输字节|
|存储|事件/Outbox/账本/历史制品/状态快照累积|建立保留与归档合同；旧旅程仍依赖的制品、幂等和终态证据不可随意清理|

## 5. 故障演练推演

本节是基于源码的演练设计，**没有停止容器、修改共享数据库或执行真实故障注入**。

|故障|期望行为|当前源码会怎样|缺口/验收|
|---|---|---|---|
|Redis 宕机|保持幂等身份，不重复产生报价结果或透明切换权威|REDIS 模式执行 claim/complete 失败向上抛；JDBC 模式为另一实现，并非自动回退|注入响应丢失和进程退出；验证原键结果及恢复策略，确认实际启用模式|
|Kafka 不可用|接收/拒绝有边界，已接收事件可恢复|Outbox 持久记录、租约、有界发送及重试；Flink checkpoint/事务 sink 受阻；重试耗尽可进入隔离状态|验证背压阈值、磁盘预算、死信重放和恢复追赶，不能只看 broker 恢复|
|MySQL 主库切换|同键查回已提交结果，临时失败可恢复|HTTP 事务失败，提交结果可能未知；Journey SQL 异常可将普通合法信号转死信（R05）|提交后断响应、resolver 断连分别验收；不把未知提交直接当未执行|
|第三方超时|成功/失败未知时沿原幂等键查询或重试|Award relay 使用 sourceRequestId、请求超时、租约版本和退避；响应不确定仍可能重试|真实渠道证明去重、查询、撤销/终态语义；DEAD 不等于渠道一定未执行|
|单实例宕机|接管后不重复业务效果，实例状态可续办|发布 slot/Outbox 持久化；资金回执/版本校验；Flink checkpoint 恢复机制存在|在提交前后和发送成功回写前杀进程；本轮未验证实际 HA 配置和接管时长|
|多实例同时恢复|避免全量回源/重试压垮共享数据库|每个 Decision 副本从 EPOCH 装载投影；每个 Job resolver 重建本地缓存|先限制启动并发，验收 DB 峰值、追平时间及旧执行者 fence|
|消息积压|保持顺序和原业务身份，超过期限明确隔离|现有版本/收据/TTL/timer重试；Journey state 过期清理，历史制品仍为恢复依赖|量测积压超过 TTL/保留窗的行为；定义超窗人工处置，不承诺无条件重放|
|配置中心不可用|明确实际配置来源和失联策略|本仓主链未见 Nacos/Apollo 依赖；配置主要为启动环境/部署配置，运行开关与版本在数据库|Nacos/Apollo 演练不适用；改测真实 runtime store、启动配置和信任材料缺失行为|

## 6. 短期 / 中期 / 长期改进

|阶段|建议范围|完成条件|
|---|---|---|
|短期：先封住已复现错误|R01 限制支持语义与比例字段；R02 必需路由；R03 类型/算子矩阵；R04 无损编辑；R08 补相应门禁|同一输入预览与编译执行结果一致；非法图发布前拒绝；合法定义往返语义不变；类型/路由边界用例通过|
|短期：补恢复分类|R05 区分基础设施错误与非法消息，设置连接/查询预算，保留原信号身份|隔离环境短断 DB 后合法信号自动恢复或进入明确持久重试；无重复副作用、无默默漏推进|
|中期：形成同源语义内核|仿真、编译和线上执行使用同一受限执行模型；节点版本/类型/支持能力有唯一来源；加入 source map 与原因码|黄金样本覆盖条件、比例、冲突、阈值、边界；旧版本可解释，升级有兼容与回滚证据|
|中期：完善运行和业务合同|R06 有界投影；R07 Campaign 多状态；人群 snapshot/segment 标识、保留窗、外部未知结果恢复|真实撤销/过期链路、冷启动与积压恢复、分槽发布观察状态可验证|
|长期：按变化点整理内部职责|R09 拆内部用例协作者；有量化负载/团队/故障隔离收益后再评估服务调整|资金事务不变量保持；服务调整有独立收益和成本证据，而非按类行数或菜单数量决定|

建议保留 Decision 的在线进程、编译 worker、Flink 作业和资金/触达服务；Rule 求值保持纯库，Activity/Release 先在 Control 内整理。后续若编译/渠道/人群计算实测争抢资源，先考虑已有进程/worker 的预算和隔离，再讨论新服务。

这些是整改建议，并非已批准实施计划。实施前真正需要确定的业务项是：支持的 DSL 子集与存量制品处置、Campaign 多状态含义、人群标识合同、实例/事件/制品保留窗口及容量目标。无需为本轮审查补造这些决策，也无需先引入新框架。

## 7. 源码证据与验证索引

|证据|定位|支持的结论|
|---|---|---|
|E01|[OfferPolicyGraphCompiler:28](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/rule-compiler-worker/src/main/java/com/acme/marketing/compiler/application/OfferPolicyGraphCompiler.java#L28)、[GraphSimulationService:48](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/marketing-control-service/src/main/java/com/acme/marketing/control/application/GraphSimulationService.java#L48)|R01 条件/比例语义差异|
|E02|[DecisionApplicationService:87](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/offer-decision-service/src/main/java/com/acme/marketing/decision/application/DecisionApplicationService.java#L87)、[RuleCompilerService:96](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/rule-compiler-worker/src/main/java/com/acme/marketing/compiler/application/RuleCompilerService.java#L96)|线上资格范围；编译成功可形成签名制品|
|E03|[GraphValidator:93](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/runtime-spi/lowcode-language-core/src/main/java/com/acme/marketing/lowcode/validation/GraphValidator.java#L93)、[JourneyCompiler:13](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/runtime-spi/journey-runtime-spi/src/main/java/com/acme/marketing/journey/JourneyCompiler.java#L13)、[JourneyNode:14](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/runtime-spi/journey-runtime-spi/src/main/java/com/acme/marketing/journey/JourneyNode.java#L14)|R02 校验与运行必需路由|
|E04|[AudienceService:159](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/audience-service/src/main/java/com/acme/marketing/audience/application/AudienceService.java#L159)|R03 类型校验与求值|
|E05|[graph.ts:24](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/frontend/apps/console/src/features/designers/graph.ts#L24)、[LowCodeDesigner:129](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/frontend/apps/console/src/features/designers/LowCodeDesigner.tsx#L129)|R04 保存模型及真实接线|
|E06|[JdbcJourneyPlanResolver:63](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/jobs/journey-runtime-flink-job/src/main/java/com/acme/marketing/jobs/journey/JdbcJourneyPlanResolver.java#L63)、[JourneyRuntimeProcessFunction:35](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/jobs/journey-runtime-flink-job/src/main/java/com/acme/marketing/jobs/journey/JourneyRuntimeProcessFunction.java#L35)|R05 同步 I/O 与异常去向；历史激活验签|
|E07|[AudienceMembershipProjection:44](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/offer-decision-service/src/main/java/com/acme/marketing/decision/runtime/AudienceMembershipProjection.java#L44)、[DecisionMapper:214](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/offer-decision-service/src/main/resources/mapper/DecisionMapper.xml#L214)|R06 投影复制、装载与查询|
|E08|[Campaign:35](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/marketing-control-service/src/main/java/com/acme/marketing/control/domain/Campaign.java#L35)、[ControlApplicationService:60](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/marketing-control-service/src/main/java/com/acme/marketing/control/application/ControlApplicationService.java#L60)|R07 状态模型与用例范围|
|E09|[ReleaseApplicationService:180](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/marketing-control-service/src/main/java/com/acme/marketing/control/application/ReleaseApplicationService.java#L180)|发布 ACK、锁、CAS、Outbox；REFERRAL 发布拒绝见同文件 85 行|
|E10|[BenefitFundingService:888](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/benefit-funding-service/src/main/java/com/acme/marketing/benefit/application/BenefitFundingService.java#L888)、[资金表约束:15](../../services/benefit-funding-service/src/main/resources/db/migration/V1__benefit_funding.sql#L15)|资金版本校验和最终完整性约束|
|E11|[EventOutboxPublisher:81](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/event-gateway-service/src/main/java/com/acme/marketing/eventgateway/infrastructure/EventOutboxPublisher.java#L81)、[FlinkKafkaJobRunner:34](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/jobs/flink-job-support/src/main/java/com/acme/marketing/jobs/support/FlinkKafkaJobRunner.java#L34)|可靠交接和分段 MQ 保证|
|E12|[AwardIntentRelay:81](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/benefit-funding-service/src/main/java/com/acme/marketing/benefit/infrastructure/AwardIntentRelay.java#L81)|稳定幂等身份、租约、超时、重试和熔断|
|E13|[DecisionRequestExecutor:72](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/offer-decision-service/src/main/java/com/acme/marketing/decision/application/DecisionRequestExecutor.java#L72)|JDBC/Redis 幂等模式和故障行为|
|E14|[TenantContextFilter:53](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/platform-web/src/main/java/com/acme/marketing/platform/web/TenantContextFilter.java#L53)、[Compose:3](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/deploy/compose.yaml#L3)、[secure:13](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/deploy/compose.secure.yml#L13)、[Helm:6](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/deploy/helm/marketing-platform/values.yaml#L6)|OIDC/DEV 信任边界和部署条件|
|E15|[ArchitectureRulesTest:39](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/architecture-tests/src/test/java/com/acme/marketing/architecture/ArchitectureRulesTest.java#L39)、[pom](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/architecture-tests/pom.xml)|R08 测试覆盖范围|
|E16|[AudienceMembershipConsumer:25](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/offer-decision-service/src/main/java/com/acme/marketing/decision/runtime/AudienceMembershipConsumer.java#L25)、[AudienceService:98](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/audience-service/src/main/java/com/acme/marketing/audience/application/AudienceService.java#L98)|segmentId/snapshotId 合同差异，消费者默认开关|
|E17|[JourneyRuntime:15](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/runtime-spi/journey-runtime-spi/src/main/java/com/acme/marketing/journey/JourneyRuntime.java#L15)、[OfferSelector:16](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/runtime-spi/decision-runtime-spi/src/main/java/com/acme/marketing/decision/pricing/OfferSelector.java#L16)|信号去重集合与组合求值上限|

### 本轮实际验证

1. 离线运行 `./mvnw -o --batch-mode --no-transfer-progress -pl architecture-tests -am -Dtest=ArchitectureRulesTest -Dsurefire.failIfNoSpecifiedTests=false test`：**9 项通过，0 失败/错误/跳过**，含 7 个 ArchUnit 规则和 2 个 JUnit 检查。该命令只运行指定检查，未运行服务数据库测试。
2. 纯 Java 探针调用当前构建的真实仿真、编译与定价代码，复现 R01 两个差异；内存字段类型提供者配真实校验/求值复现 R03。以下是缺陷复现成功，不能表述为这些业务已验收通过：

   ```text
   OFFER_CONDITION_FALSE validatorIssues=0 previewDiscountMinor=0 compiledPricingDiscountMinor=1000
   OFFER_PERCENT_FIELD validatorIssues=0 previewDiscountMinor=1000 compiledPricingDiscountMinor=0
   AUDIENCE_INSTANT_GT validation=ACCEPTED evaluation=NumberFormatException
   ```

3. 用仓库已有 TypeScript 编译器转译真实 graph.ts，并执行两个纯函数往返样本：发现 1 条悬空边，以及非空旅程 variables 变成空对象。没有浏览器、数据库或网络副作用。
4. 对 420 个 Java 主源码做显式 import 检查，对声明的 116 张表和 247 处字面 SQL 写做所属模块比对；在识别范围内服务间直接 import、Repository/持久领域对象跨服务 import、跨 Owner 写均为 0。161 处 wildcard import 未解析，动态 SQL、反射、实际数据库 ACL 不在证明范围。
5. 扫描发现 jobs 中 3 处内联 SQL（JdbcJourneyPlanResolver:112/132/153），当前原生 SQL 检查未覆盖。

### Day-0 Fitness 结论

|检查|结论与限制|
|---|---|
|Repository / Entity 跨边界|限定显式 import 扫描未发现；非全域证明|
|跨域直接写 / 数据 Owner|限定字面 SQL 与唯一声明 Owner 比对未发现；未验证实际账号权限、动态访问|
|依赖方向 / 循环|原生 ArchUnit 对本次加载类通过；jobs/网关不在该依赖集，不可称全仓无环|
|跨模块契约|已见 SPI、签名制品、事件/HTTP 端口；语义合同存在 R01–R04，外部合同缺口单列|
|事务不变量 Owner|资金与发布 slot 的代码归属明确；并发、主切、外部副作用未在本轮实测|
|服务抽取就绪|无负载、团队 Owner、独立发布收益和完整兼容证据；不打虚假就绪分数|

详细探针源码、原始日志、静态扫描脚本及开始时 994 个既有文件的 SHA-256 在本机临时证据目录：`/var/folders/fh/4kj3hbdd6q1dbswkdz5l_fsr0000gn/T/marketing-architecture-review-20261018-1htkwsqy`。本文保留关键结果，临时目录不作为永久源码证据。

产物校验：65 处源码链接及行号有效，开始时记录的 994 个既有文件 SHA-256 无变化，`git diff --check` 通过。本轮新增审查报告并更新本机忽略的进度记录，业务代码未修改。

未执行：完整服务回归、真实数据库/渠道端到端、故障注入、压测、生产配置审计、依赖安全扫描。旧报告的前端测试/Lint 结果未当作本轮重新执行的结果。

```yaml
SKILL_HANDOFF:
  status: COMPLETED
  gate: REVIEW_COMPLETE_WITH_CONFIRMED_RISKS
  produced:
    - .cursor/project-analysis/architecture-risks.md
  updated:
    - CODEX_PROGRESS.md
  unresolved:
    - installed_offer_artifact_exposure
    - audience_identity_contract
    - campaign_aggregate_state_rules
    - runtime_capacity_and_retention_targets
    - actual_failure_recovery_and_external_terminal_contracts
  downstream_requirements:
    - scoped_implementation_request
    - affected_business_contracts
    - isolated_integration_validation
  recommended_next: scoped_correctness_remediation
```
