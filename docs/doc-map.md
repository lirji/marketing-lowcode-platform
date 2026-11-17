# 文档同步地图

最新增量同步：2026-11-18，`completion-20261116/S11e–g4b`，基线 `d57a5722bcef30e2cb603a39caa9201a4b921bf5`。控制面组合准备、成员归属、V8迁移、完整签名导出、运行端精确准备、V9组合证据/当前就绪计算、V10同slot保护、整体激活授权签名与Decision V4/Journey V3持久接收、固定参与凭证及Decision V5/Journey V4运行端slot保护对应 [组合设计](delivery/completion-20261116/ATOMIC_RELEASE_DESIGN.md)、[发布接口](operations/RELEASE_WIZARD_API.md) 和 [机器合同](../marketing-contracts/src/main/resources/openapi/marketing-api.yaml)。当前支持准备及授权持久接收，尚不支持线上整体激活；未迁移共享库或部署。

上次阶段同步：2026-11-16，`completion-20261116/S04`，源码基线 `94d133442733587e3aa0acb37fd27194f7d5195a`。本次同步 CI 基础设施/目录合同样例与验收边界；S03 真实本地 R1 已通过，当前本机多数业务镜像仍为历史版本，不能视为新镜像或空环境全链路已通过。

| 代码区域 | 权威入口 | 同步内容 |
| --- | --- | --- |
| deploy/compose.yaml、bootstrap/submit-flink-jobs/flink-state/smoke/acceptance-r1 | [README](../README.md)、[运行手册](operations/RUNBOOK.md) | 首发 readiness、显式提交、恢复计划、检查点、传输重试与未知写结果 |
| .github/workflows、tests/fixtures/dev-infra、benefit-catalog、ci-resources | [CI 固定样例说明](../tests/fixtures/dev-infra/README.md)、[README](../README.md) | 临时基础设施、合同样例验收范围、资源覆盖、临时凭据与诊断脱敏 |
| 本轮验收与后续范围 | [实施进度](delivery/completion-20261116/PROGRESS.md) | 已通过证据、实际失败与待办，不将配置变更写成已部署 |
| Event 演示 outbox 与种子验证 | [演示数据](../README.md#本地演示数据) | PUBLISHED终态与深度分桶一致，不把展示记录送入实时队列或计作积压 |

本次增量同步基线：`6b9092feed4d49696aa4498d118a14f241268568`；日期：2026-10-19；任务：pending-files-update。范围为登录说明、演示种子及原有 45 份历史分析/评审；不代表重新核验全部业务或环境。

| 本次区域 | 权威入口 | 同步内容 |
| --- | --- | --- |
| 全景分析、架构评审与复审 | [当前状态](marketing-platform-analysis/CURRENT_STATUS.md) | 按问题映射已修复、部分完成、仍待合同；各报告保留历史基线并固定历史源码链接 |
| Casdoor/DEV 登录 | [README](../README.md) | 区分认证模式，凭据由环境变量注入 |
| seed 脚本、SQL、隔离验证 | [演示数据说明](../README.md#本地演示数据) | 默认控制台租户、当前 DSL 样例、演示账户范围与可重复验证 |
| 本次改动与交付 | [进度](delivery/pending-files-update/PROGRESS.md) | 验证范围、结果及 Git 交付证据 |

## 已有架构正确性同步

以下记录对应 2026-10-18 的 architecture-correctness 任务，起始源码基线 `1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c`。

| 代码区域 | 权威说明 | 本次同步 |
| --- | --- | --- |
| lowcode-language-core、decision-runtime-spi、控制面/编译器 | [低代码运行时](lowcode/RUNTIME.md) | 支持子集、比例兼容、试算边界、Journey 必需路由、类型化人群、旧制品门禁 |
| Journey Flink、Decision 人群投影 | [运行手册](operations/RUNBOOK.md) | 故障分类、超时配置、缓存容量/新鲜度、事务隔离与未验证性能边界 |
| ReleaseApplicationService | [发布向导](operations/RELEASE_WIZARD_API.md) | 重新编译错误及处理 |
| 同步决策面 | [架构](architecture/README.md) | 明确本服务数据库回源依赖 |
| 本轮修复与架构门禁 | [交付状态](delivery/architecture-correctness/PROGRESS.md) | 验证、复审、旧债基线、Git 交付 |

旧分析报告保留为历史证据；活动多状态、受众标识映射及外部终态合同、资金服务职责整理继续按交付状态列为后续事项。
