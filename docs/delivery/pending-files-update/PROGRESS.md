# 历史文档与演示数据更新

## 目标与授权

2026-10-19 用户要求“更新这些文件，然后提交”。范围为原有 48 个未提交文件及必要说明/验证；在原目录任务分支提交，按持续授权正常合入并推送 main。不部署、不向现有开发库灌数，不重做架构。

## 当前状态

- 任务分支：`chore/refresh-pending-docs-and-seeds`；起始提交：`6b9092feed4d49696aa4498d118a14f241268568`。
- [x] 核对原有 48 文件与当前交付记录并备份原始指纹。
- [x] 更新登录说明；45 份历史报告逐章补充最新处理状态，624 条历史源码链接固定到原提交。
- [x] 更新 seed 预检、资金范围、DSL 与说明；必要关联修改包括 system-mock.sql，新增隔离验证工具。
- [x] 当前 SQL、shell、图定义与文档检查通过，结果见下表。
- [x] 演示数据与使用说明提交 `f885d11`。
- [ ] 文档提交、合入并推送 main。
- 已验证；演示数据已本地提交，文档待提交，尚未推送；无运行中验证。

## 验证结果

| 检查 | 结果与范围 |
| --- | --- |
| `python3 scripts/verify-seed-data.py --output-dir <证据目录>` | PASS；本机 mysql:8.4.11，无网络/端口/持久卷的临时容器；37 份现有迁移建立 9 库，执行完整种子两次，9 项断言通过 |
| 数据隔离 | 同租户非演示账户、其他租户同名资源的余额、分桶及预留分配保持不变 |
| 重复导入和资金 | 各表行数不增长；预算账户/分桶、预留项/分配逐项守恒；6 组 16 路演示分桶；两租户分别 3/7 个活动 |
| Java 图定义 | 从隔离库导出 8 个 graph_json，用当前源码 javac 编译的 GraphValidator/OfferGraphInputGuard/OfferPolicyGraphCompiler/JourneyCompiler 验证通过；草稿仅校验/试算，可发布示例另外编译 |
| seed shell 控制流 | 4 项 PASS：正常三段顺序；缺补充文件/租户模板变化时写库前失败；第二段 MySQL 失败停止并清理临时文件 |
| 静态检查 | bash -n、Python 编译、文档本地链接检查通过；最终暂存区 diff/凭据复查在提交前执行 |
| 秘密检查 | 53 个任务文件原明文密码及高风险凭据模式检查 0 命中；没有新增 .env 或真实凭据；本机未安装 shellcheck/gitleaks，未声称其通过 |

验证过程中，首个临时 javac 命令缺 marketing-contracts 源路径，补齐后发现原种子 fundingRules 使用逗号、当前契约使用分号；两个 SQL 已修正并重新通过真实导入与 Java 验证。保留首轮失败日志以说明修正依据。Offer 演示改为支持的单链，比例节点使用 offer.percentage/discountBasisPoints；出资 5000+5000 基点；Journey 等待节点使用 journey.wait-timer。没有放宽产品校验器。

本轮没有改 Java/前端产品逻辑，未重跑全仓构建、整栈业务、生产容量或灾备；历史验证引用明确绑定原交付。种子状态、签名和回执只是本地展示数据，不证明真实发布/发奖链路通过。

## 文档和范围

- 当前报告权威入口：[当前状态](../../marketing-platform-analysis/CURRENT_STATUS.md)；[文档地图](../../doc-map.md)。
- 已修复/部分完成/待合同分别映射，原历史发现保留。DEV QueryClient、前端 DEMO_MODE、跨系统身份/终态和容量恢复等未被误标完成。
- README 凭据改为调用方环境变量；不轮换密码、不修改 .env。资金补桶仅覆盖两个演示租户的明确资源/申请。
- 采用规范：`~/.codex/architecture-standards.md` 的业务建模、持久化、接口契约、并发安全、发布治理；技能 project-documentation、task-git-delivery、update-progress-docs。

## 证据与恢复

- 证据目录：`/var/folders/fh/4kj3hbdd6q1dbswkdz5l_fsr0000gn/T/marketing-pending-update-20261019-q1uzr6lw`。包含 BASELINE.json、原文件私密备份、REPORT_UPDATE.json、seed-validation-final.log、seed-result.json、seed-graphs.jsonl、graph-validation-final.log、shell-result.json、doc-links.json、secret-check.json。
- 两个本次创建的隔离 MySQL 容器均已随验证结束释放，现有开发库/容器未变；未创建 worktree。
- 下一步：提交报告、状态索引与本进度，正常合入推送 main，记录回执。恢复先核对 Git 与此进度，不重复灌现有开发库。
