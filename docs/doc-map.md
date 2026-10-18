# 文档同步地图

本次增量同步基线：`1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c`；日期：2026-10-18；任务：architecture-correctness。仅列本次受影响区域，不代表全仓文档已核实。

| 代码区域 | 权威说明 | 本次同步 |
| --- | --- | --- |
| lowcode-language-core、decision-runtime-spi、控制面/编译器 | [低代码运行时](lowcode/RUNTIME.md) | 支持子集、比例兼容、试算边界、Journey 必需路由、类型化人群、旧制品门禁 |
| Journey Flink、Decision 人群投影 | [运行手册](operations/RUNBOOK.md) | 故障分类、超时配置、缓存容量/新鲜度、事务隔离与未验证性能边界 |
| ReleaseApplicationService | [发布向导](operations/RELEASE_WIZARD_API.md) | 重新编译错误及处理 |
| 同步决策面 | [架构](architecture/README.md) | 明确本服务数据库回源依赖 |
| 本轮修复与架构门禁 | [交付状态](delivery/architecture-correctness/PROGRESS.md) | 验证、复审、旧债基线、Git 交付 |

旧分析报告保留为历史证据；活动多状态、受众标识映射及外部终态合同、资金服务职责整理继续按交付状态列为后续事项。
