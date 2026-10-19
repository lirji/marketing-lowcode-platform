# 营销低代码平台全景梳理与架构深度分析

## 2026-10-19 更新

核心语义缺陷已有代码修复与验证；生产就绪、身份/活动状态合同及真实外部链路仍未闭环。

当前源码核对基线 `6b9092f`；[完整状态与证据](CURRENT_STATUS.md)。以下保留原评审基线的发现、严重度和测试结果；其中“当前/待实施”均指原评审当时。历史源码链接已固定到原提交，后续实现请从最新状态索引进入。

## 历史报告正文（1392ca9 基线）

- 仓库：`marketing-lowcode-platform`；源码基线：`1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c` 加当前未提交工作区。
- 分析日期：2026-09-22；模式：`READ_ONLY_ANALYSIS`。
- `CONFIRMED`＝源码/配置/测试直接证据；`INFERRED`＝据证据推断；`UNKNOWN`＝尚缺证据。代码存在不等于已部署或已通过真实链路验收。
- 本章遵循用户提示词；产品代码、数据库、运行配置、Git 提交与推送均不变。

## 阅读结论

**INFERRED：项目已经形成领域专用营销低代码运行平台的主体，超出了活动 CRUD；但它不是所有玩法均已闭环、可直接宣称生产就绪的成熟通用平台。**

证据链是：有版本的 GraphDefinition → 校验与审核 → 编译签名制品 → manifest/ACK/单调激活 → 在线 OfferPolicy 或固定版本 JourneyPlan → 持久化副作用分发。当前关键缺口包括 Offer 仿真与线上语义偏差、裂变真实接线、人群标识映射、活动生命周期和部分设计器闭环。详见 [05规则](05-rule-engine.md)、[07旅程](07-journey-engine.md)、[09发布](09-publish-version-rollback.md)、[15风险](15-architecture-risks.md)。

## 1. 这个系统是什么？

**CONFIRMED**：面向运营和审核者的营销工作台，加上优惠决策、旅程执行、资金权益、触达、人群与衡量运行模块。主仓有 11 个服务模块、3 个业务 Flink 作业、可移植 runtime SPI 与 React 控制台；模块存在不等于部署均启用。

## 2. 为什么需要这个系统？

**INFERRED**：重复的营销活动开发需要抽象为可版本化配置；优惠、触达和发奖又不能完全交给任意页面配置。平台提供“配置效率”和“审核、资源完整性、可追溯运行”之间的受控边界。没有业务规模数据，不编造节省人日或收入提升。

## 3. 解决了哪些业务问题？

**CONFIRMED**：组织活动目标与数据范围；保存和审核规则；安装及切换运行版本；按购物车选择/分摊优惠；通过状态机等待和触达；预占/确认/退款与账本；收集事件并更正衡量事实。外部订单、支付、画像、权益渠道权威由其他系统承担。

## 4. 核心领域有哪些？

Campaign/Definition、Rule/Pricing、Audience、Journey、Release、Benefit/Funding、Engagement、Measurement、Event admission、Referral。平台公共身份/合同/审计属于跨领域基础能力，不宜重新变成业务总控大类。[领域模型](03-domain-model.md)。

## 5. Activity / Rule / Audience / Journey 是什么关系？

Campaign 是目标与归属容器；Rule 决定本次可得优惠；Audience 提供目标群体资格；Journey 编排事件、等待和副作用。Campaign 下定义按方言独立版本化；在线报价不必经过 Journey。它们不是统一的一张“活动配置表”。

## 6. 一场活动如何从设计走到运行？

画布→图定义→不可变定义版本→校验→审批和条款→编译制品→stage→runtime 预热/签名 ACK→activate→通知/reconcile→流量选择。**CONFIRMED** 关键代码路径存在；**UNKNOWN** 当前环境完整活动验收。Campaign 的状态字段尚未确认随着定义审核和发布完整更新，不能用种子 ACTIVE 状态作证明。

## 7. 用户如何进入营销 Journey？

**CONFIRMED** STREAM 路径：受信调用方发送 JOURNEY_SIGNAL/START 和 PlanReference→接入收据/Outbox→Kafka→Flink keyed state→验证已激活计划→实例开始。后续 EVENT 或 processing-time timer 推进。未确认通用“任意订单事件自动选活动/人群/Journey”的规则路由层。

## 8. Rule Engine 如何工作？

常规线上报价先把优惠图编译为 OfferPolicy 列表，运行时过滤资格并执行分阶段定价、互斥选择和精确分摊。Drools/DMN 有独立真实适配器，但不应宣称所有线上报价都调用它们。**P1：仿真遍历图分支，编译却只提取优惠节点；百分比参数名称也不同。**签名完整性无法弥补该语义差距。[规则反例](05-rule-engine.md)。

## 9. Journey Engine 如何工作？

自研纯状态机+Flink 状态托管；单 currentNodeId，支持分支、事件/计时等待、触达/发放/webhook 命令，实例固定版本。没有通用并行 join 证据；REPEAT 存在运行枚举但目录/编译映射未闭环。COMPLETED 表示走到 END，不等于所有外部动作成功。

## 10. 发布与版本如何实现？

DefinitionVersion 表达设计版本，Artifact 表达字节和来源，generation 表达运行制品代次，activationSequence 表达指令顺序。发布锁/CAS/Outbox 保证单库原子；多 runtime 最终收敛。权限、审核、签名 ACK、ABI/制品闭包及容量检查均有源码。[09章](09-publish-version-rollback.md)。

## 11. Rollback 真正回滚什么？

用新 activationSequence 指向留存且曾激活、未失效的旧 generation，改变对应 runtime slot 的新流量选择。旧 Journey 实例继续固定旧计划；人群成员变化、已发券和已发短信不自动逆转。配置回滚、运行实例迁移和业务补偿必须分开。

## 12. 最复杂的五个技术问题是什么？

1. 低代码配置到运行制品的语义一致和信任绑定。
2. 回滚允许旧代次但指令顺序始终向前。
3. 长时 Journey 状态、计时器和外部副作用恢复。
4. 多优惠互斥选择、分摊舍入和资源账本守恒。
5. 跨系统奖励未知结果、租约接管和原身份恢复。

逐项 Problem/Why Hard/Solution/Evidence/Trade-off/Failure/Improvement 见 [17章](17-core-technical-challenges.md)。

## 13. 最大的五类风险是什么？

- R01：Offer 审批预览与线上优惠可不一致（P1，源码差异确认）。
- R02：裂变仍缺真实 READY/身份/SKU/参与许可；开放门禁前不具备上线条件（能力缺口）。
- R03/R04/R10：活动生命周期、人群类型语义和在线资格键映射未完全闭环。
- R08：单次步数有界但长期去重状态/人群热集合容量仍需治理（容量风险，未实测事故）。
- R05/R06：DEV 租户缓存和当前 Lint 门禁失败，影响隔离与工程验收。

未确认 P0。风险登记共 10 个父项，部分拆子结果；不将“10项”冒充 10 个同等大小的修复任务。

## 14. 最值得保留的五个设计是什么？

设计/制品/运行隔离；generation/activationSequence 解耦；Journey 状态推进与副作用命令分离；资金数据库约束/版本/fence/账本；无可信依据时保持裂变默认拒绝。它们有真实代码，不是仅靠文档命名。[17章](17-core-technical-challenges.md)。

## 15. 下一阶段最值得演进的五个能力是什么？

同源规则编译与求值；真实活动/人群生命周期；按合同接通裂变及 DMN 等玩法；跨链可解释性与受控恢复；按实际目标验证长期容量/故障恢复。先修已知正确性与工程门禁，不优先引入更多服务或中间件。[16章](16-architecture-evolution.md)。

## 16. 高级 Java 面试最值得讲什么？

先讲完整业务与失败窗口，再讲规则版本/发布回滚、旅程恢复、精确分摊、Outbox/业务幂等和未知结果。提供 8 个故事、3 条 STAR 素材和 10 个分层追问。个人职责、最初方案与线上成绩尚未提供，素材不自动变成个人履历。[18面试资产](18-interview-assets.md)、[19追问](19-interview-questions.md)。

## 验证结论

本次对话实测前端 83 项和纯模块/合同 130 项通过，前端构建通过；Lint 因 7 条警告失败。后端范围仅 common/contracts/lowcode/referral SPI，不包括整仓服务数据库测试；未执行当前环境 E2E、远程 CI、压测、渠道实发或灾备恢复。[证据和命令](01-project-landscape.md)。

## Marketing Low-Code Platform Exploration Report

状态表示分析覆盖/证据边界，不是功能验收 PASS。

|维度|覆盖结果|
|---|---|
|Repository Coverage|核心模块、配置、SQL、前端、CI、测试交叉扫描；非逐文件全审计|
|Business Architecture|已还原；7 条调用链，02章|
|Domain Model|已还原，03章|
|Activity|已还原；生命周期接线缺口，04章|
|Rule Engine|已还原；语义差异 R01，05章|
|Audience|已还原；全量计算/标识映射缺证，06章|
|Journey|已还原；单路径、循环接线限制，07章|
|Workflow Runtime|已还原；真实灾备/失败恢复产品入口缺证，07/10/14章|
|Low-Code Model|已还原；动态表单/插件能力受限，08章|
|Publish|已还原；裂变拒绝门禁保留，09章|
|Version|已还原多版本轴，09章|
|Rollback|已区分指针/实例/业务补偿，09章|
|Event Architecture|已还原 topic 与处理路径，11章|
|Consistency|已区分局部事务和最终收敛，11章|
|Idempotency|已还原键/hash/唯一约束/fence，11章|
|Data Model|核心表、所有权及逻辑 ER，12章|
|Extensibility|8 类扩展矩阵，13章|
|Observability|已评估解释能力及缺口，14章|
|Recovery|机制与未知环境证据已分开，14章|
|Architecture Risk|P1/P2 10 父项及失败场景，15章|
|Architecture Evolution|渐进路线、16维成熟度；未执行改造，16章|
|Interview Assets|8故事/3 STAR/5难点/5设计/10追问，17–19章|
|Evidence Coverage|源码定位+SHA索引、测试范围、未知项；01章附录|

**Final Status: COMPLETE_WITH_GAPS**

含义：21 份报告的分析范围已交付；系统未实现的能力与无法访问的真实运行证据被显式保留，没有用猜测补齐。

## Evidence Gap 与继续确认所需材料

|Gap|当前未知|需要什么|
|---|---|---|
|G01|外部身份、SKU、风险、确认/取消权威|auth/交易/权益/风控平台已签收合同及只读证据|
|G02|snapshotId 与 segmentId 在线映射|一组脱敏 source event、snapshot、policy、projection 与决策 trace|
|G03|整平台真实端到端可用性|明确测试环境、运行配置的非秘密摘要和 E2E 验收权限|
|G04|容量、长期状态、热点租户|业务 SLO、数据分布、硬件、隔离压测环境和报告|
|G05|恢复目标与实际恢复能力|隔离恢复环境、checkpoint/备份完整性、RTO/RPO 目标和演练结果|
|G06|安全/依赖兼容与漏洞状态|目标版本清单、扫描结果、官方兼容核验；本轮未联网审计|
|G07|个人贡献及历史决策|本人负责范围、设计讨论/提交记录、真实结果；不可从代码反推个人业绩|

## 交付边界

本轮只新增 `docs/marketing-platform-analysis/` 报告并记录根进度。保留既有 README、seed.sh 和 seed SQL 改动。没有修改产品代码/测试/配置/数据库，没有 Git 提交或推送，没有部署。遵循用户指定目录与 CONFIRMED/INFERRED/UNKNOWN 格式，未另建竞争的 `.engineering/analysis/` 四件套。

## 报告导航

建议先读本摘要，再读 [业务调用链](02-business-architecture.md)、[规则引擎](05-rule-engine.md)、[Journey](07-journey-engine.md)、[发布回滚](09-publish-version-rollback.md)；评审看 [风险](15-architecture-risks.md) 和 [演进](16-architecture-evolution.md)，面试看 [素材](18-interview-assets.md)、[追问](19-interview-questions.md)，总图看 [架构图册](20-final-architecture.md)。全量源码定位、指纹和提示词覆盖映射在 [01章](01-project-landscape.md)。

分析产物结构与链接检查通过；Mermaid只做基本结构检查，未渲染。此检查通过不改变功能门禁失败或运行证据缺口。
