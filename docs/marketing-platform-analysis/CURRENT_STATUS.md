# 分析与评审当前状态

同步日期：2026-10-19；源码核对基线：`6b9092feed4d49696aa4498d118a14f241268568`。本次同步覆盖原有 21 份全景分析、23 份架构评审、1 份复审，以及登录说明和演示数据；不重新评定整个系统为生产就绪。

## 阅读方式

- 各历史报告先给出本章更新，再保留原始发现。原文的“当前”“未修复”、严重度、测试数字、行号和 Handoff 都属于 `1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c` 历史基线，不能直接套用到现在。
- **已修复代码**以[架构整改交付](../delivery/architecture-correctness/DELIVERY.md)、[验证证据](../delivery/architecture-correctness/EVIDENCE.md)及[进度](../delivery/architecture-correctness/PROGRESS.md)为准；历史风险仍保留，以解释变更原因。
- **部署事实**按各次部署记录的日期和范围读取，不能把一次 healthy 记录当作当前环境状态。本轮未部署或重新运行整栈验收。
- 全景分析的 `Rxx`、架构评审的 `Axx/Txx/Fxx`、10 月复审的 `Rxx` 是不同编号体系；按下表的问题名称关联，禁止只按编号合并。

## 风险与整改对应

| 问题 | 历史编号 | 截至同步基线的状态与边界 |
| --- | --- | --- |
| Offer 预览/编译语义、比例字段 | 全景 R01；评审 A01；复审 R01 | **代码修复并验证**：共享候选构建和定价；不支持的条件/分支拒绝；比例别名冲突拒绝；旧制品激活设门禁。仍只支持声明的子集，存量制品需正常重编译、审批、发布。 |
| Journey 缺必需路由/悬空节点 | 评审 A02；复审 R02 | **代码修复并验证**：编译前检查必需端口、重复/悬空边及孤立节点；不表示任意工作流或并行 BPMN 已支持。 |
| Audience 字段类型与算子 | 全景 R04；评审 A06；复审 R03 | **代码修复并验证**：按数字、日期、布尔、字符串类型校验并求值，前端限制可选算子。 |
| 编辑保存丢 ID、版本、变量和注释 | 评审 A09；复审 R04 | **代码修复并验证**：保留既有元数据及具名端口；完整 Schema 升级/迁移框架仍非已交付能力。 |
| Journey 临时存储故障/同步 IO | 评审 A04；复审 R05 | **故障分类与等待上限已修复**：存储故障交给作业恢复，Mapper XML 承载查询；同步数据库依赖仍在，吞吐与故障预算未整体验收。 |
| 人群投影无界/撤销遗漏 | 全景 R08 的人群部分；评审 A04；复审 R06 | **代码修复并验证**：主键回源、有界缓存（默认 10000 条，最长一秒）、并发撤销和事务快照防护。高基数/冷缓存容量仍待实测。 |
| SQL/依赖门禁漏掉 jobs、网关 | 评审 A10；复审 R08 | **静态门禁已扩展并验证**；裂变既有 231 项依赖描述冻结为基线，未清理全部历史耦合，也不替代数据库 ACL 验证。 |
| 活动聚合状态、Audience 身份、外部终态 | 全景 R03/R10；评审 A05/A07/A11；复审 R07 | **仍待业务/跨系统合同**；不根据 seed 的 ACTIVE 状态认定发布闭环，不自行把 snapshotId 与 segmentId 等同。 |
| 长生命周期、跨 slot/保留与真实恢复 | 全景 R08 的旅程部分；评审 A03/A08 | **未完整验收**；保留寿命/重放/RTO/RPO、版本留存和跨 slot 原子需求的决策前置。 |
| 资金应用服务职责整理 | 复审 R09 | **后续改进**；保留现有资金事务，不因此拆分服务或改账本。 |
| DEV 身份切换缓存、前端 DEMO_MODE | 全景 R05/R09 | **保留问题**：DEV provider 未清理 QueryClient，活动 queryKey 仍固定；可选 demoMode 仍有前端数组。OIDC 清缓存不能证明 DEV 已修复；本次补数据库种子不等于移除前端 DEMO_MODE。 |
| lint/远程 CI | 全景 R06；评审 T00 | 本地 lint 的旧失败已修复；历史交付中的远程后端、前端与 CodeQL 通过。整条 CI 仍有共享 dev-infra 获取、Trivy Action 配置阻塞，见[CI 记录](../delivery/architecture-correctness/CI_RESULT.md)，不得标记全绿。 |

直接源码入口：[Offer 支持子集](../../runtime-spi/lowcode-language-core/src/main/java/com/acme/marketing/lowcode/validation/OfferGraphInputGuard.java)、[共享优惠编译](../../runtime-spi/decision-runtime-spi/src/main/java/com/acme/marketing/decision/runtime/OfferPolicyGraphCompiler.java)、[Journey 校验](../../runtime-spi/lowcode-language-core/src/main/java/com/acme/marketing/lowcode/validation/JourneyGraphValidator.java)、[人群投影](../../services/offer-decision-service/src/main/java/com/acme/marketing/decision/runtime/AudienceMembershipProjection.java)、[图编辑映射](../../frontend/apps/console/src/features/designers/graph.ts)、[身份提供方](../../frontend/apps/console/src/shared/auth/AuthContext.tsx)。

## 原路线图的执行映射

原 [T00–T12 路线](../marketing-platform-architecture-review/19-evolution-roadmap.md)仍作为候选架构目标；不能再描述为“全部尚未开始”，也不能把局部修复算作整个阶段通过。

| 任务 | 当前对应 |
| --- | --- |
| T00 工程基线 | 本地 lint、构建、测试与架构护栏已修复；远程配置阻塞保留。 |
| T01 支持子集 | 新入口校验/旧制品激活门禁已实现；存量曝光盘点及业务处置未完成。 |
| T02 同源语义 | 支持子集内共用候选构建与 PricingEngine 已实现；通用 FactRequirement/任意图 IR 未承诺。 |
| T03 Journey 编译完整性 | 既有节点必需路由与结构校验已实现并验证；扩展方言另行验收。 |
| T04 类型与身份 | 类型治理完成；权威身份映射仍待合同。 |
| T05/T06 | Campaign 多状态、跨 slot 组合发布及完整保留合同仍未交付。 |
| T07/T08/T09 | 页面可读性、故障分类、DB 等待和人群缓存部分改善；统一因果诊断、生命周期预算和真实跨系统恢复未整体验收。 |
| T10 元数据 | 旧图往返保真已修复；通用扩展注册、DMN/循环等目标未整体完成。 |
| T11/T12 | 裂变真实接线门禁保留；架构检查扩展不代表服务抽取评分或全部 18 项 Fitness 已通过。 |

Fitness 的局部证据对应 F01/F02/F03（类型部分）/F14/F16/F17；其余验收与这些函数的环境/生产部分继续按原合同补证。原路线不能替代新的实施授权和业务决策。

## 前端与验证记录

- 10 月 18 日架构整改记录：后端 563、前端 87、浏览器 24 项及 lint/构建/契约通过；这些是该次代码指纹对应的证据，本次没有重跑或冒充当前全仓结果。
- 10 月 19 日已交付控制台布局、表单与列表字段、字号、提示条和北京时间显示。依据：[界面优化](../delivery/console-polish/PROGRESS.md)、[表单规范](../delivery/form-standards/PROGRESS.md)、[字段展示](../delivery/list-fields/PROGRESS.md)、[北京时间](../delivery/beijing-time/PROGRESS.md)。浏览器夹具测试与真实 API 只读检查分别记录。
- 最近一次[前端部署记录](../delivery/frontend-rollout/PROGRESS.md)针对控制台，保留了 Decision unhealthy 等限制；不能据此关闭所有后端链路、容量或灾备事项。
- 本次登录说明、seed 输入预检、DSL 样例和演示账户范围的改动及验证，以[本次任务进度](../delivery/pending-files-update/PROGRESS.md)为准。演示数据本身不构成外部权益发放、签名发布或生产流量证据。

## 仍需推进的事项

按业务合同补齐 Campaign 状态与 Audience 身份；核查旧制品曝光并正常发布替代版本；独立处理 DEV 缓存/前端 DEMO_MODE、远程 CI 配置、真实外部终态与恢复/容量验收。既有服务拓扑和资金事务继续保留；不因这次文档同步重新选型或宣称这些事项完成。
