# 架构正确性整改

## 当前状态

2026-10-18：S1–S6 实现与本地验证通过；代码已正常快进合入并推送 origin/main（`6e3a1962080b744dc33de3c05c9113028c4e0c6a`）。本文及配套文档为交付回执。任务分支 `fix/architecture-correctness`，基线 `1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c`；在原项目目录开发，无新增工作树。

## 授权与边界

用户在架构审查后明确“开始修复吧”，授权修复已确认问题及必要回归。按持续 Git 授权分批提交，验证后正常合入并推送 main；不部署、不迁移共享库、不停用或重签线上存量制品。48 个原有改动文件指纹保持一致，包括 README、seed、旧分析与本轮审查报告，均不纳入本轮提交。

## 单元与验收

| 单元 | 结果 | 状态 |
| --- | --- | --- |
| S1 / R01 | Offer 支持子集校验、同源候选与计价、比例兼容、旧制品发布门禁；金额预览明确上下文限制 | DONE |
| S2 / R02 | Journey 缺必需路由、悬空/重复路由、孤立节点在编译前拒绝 | DONE |
| S3 / R03 | Audience 数字/日期/布尔按类型求值，前后端限制合法算子 | DONE |
| S4 / R04 | 图 ID/版本/变量/注释无损保存，真实画布具名端口可连接 | DONE |
| S5 / R05 | Journey 事件/定时器基础设施故障抛给恢复机制，坏输入走死信；Mapper XML 与有界数据库等待 | DONE |
| S6 / R06 | 主键回源、容量/TTL 上限、并发撤销与事务快照保护，取消全表轮询 | DONE |
| R08 / 架构门禁 | jobs/网关纳入检查；裂变旧耦合冻结基线，禁止新增 | DONE（既有耦合未清理） |
| 交付 | 11 个代码提交与配套文档已合入/推送 main；最终 CI 回执只更新文档 | DONE；远程配置门禁 BLOCKED |

## 最新验证

- 后端全仓 `./mvnw -o -B -ntp verify`：563 项，失败/错误/跳过均为 0；30 个 reactor 模块构建通过。
- 最后常量/错误目录整理及架构基线文件改名后，Offer 仿真/发布门禁/编译器/架构定向回归通过。
- 前端 lint 0 警告、87 项单测、TypeScript/Vite 构建通过；24 项桌面/移动浏览器测试通过（API 路由夹具，非真实跨系统 E2E）。
- 13 份 JSON 契约验证通过；合同模块 18 项测试已包含在后端总数中。
- code-hygiene 无阻塞/建议项；仓库未配置统一 formatter，结果为 IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS。未为过门禁引入格式工具或放宽 lint。
- 三路只读对抗复审已通过；发现的 6 个问题均补修并有回归，详细证据见交付报告。
- 远程 CI：后端/合同/架构、前端及两项 CodeQL 通过（核验提交 4ee9217）；共享 dev-infra 仓库 404 和既有 Trivy Action 版本不可解析阻断环境/扫描门禁。详细状态见 [CI 核验](CI_RESULT.md)，整条流水线不标记 PASS。
- 原始日志：`/var/folders/fh/4kj3hbdd6q1dbswkdz5l_fsr0000gn/T/marketing-architecture-fix-20261018-65r8nhuz`。无运行中验证进程。

## 已定取舍与未覆盖项

- S6 使用既有主键单行回源 + 默认 10000 条/最长一秒缓存；数据库失败不返回过期资格，固定条带锁合并回源。READ_COMMITTED 与成员 SELECT 清会话缓存避免旧事务快照回填。无需新表、索引或中间件。
- R08 初次扩展检查发现裂变 274 处既有依赖，归一化为 231 项基线；约束新增，不将其宣称为已完成分层重构。
- R07 活动聚合多状态、snapshot/segment 权威映射及外部终态需业务合同；R09 资金应用服务内部整理后续增量处理，保持现有事务边界。
- 未验证真实 Flink 集群 checkpoint 重启、十倍流量/冷缓存容量、生产数据/存量活跃制品；无部署与停用动作。

## 规范与恢复

已按 architecture-standards 的业务建模、持久化、契约、并发安全、缓存、Kafka、容量、发布章节执行；使用 engineering-workflows、backend-implementation、task-git-delivery、project-documentation、update-progress-docs。
恢复时先核对本文件、[交付报告](DELIVERY.md)、[验证证据](EVIDENCE.md)、git main/origin/main 和工作树。源码交付完成；后续优先补业务合同、存量制品盘点和独立运行演练，不重做本轮已验证修复。
