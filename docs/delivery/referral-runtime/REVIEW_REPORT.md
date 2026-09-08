# Code Review Report

## Scope And Diff Base
原平台 main 工作树的新增 referral-runtime-spi 与 runtime-spi/pom.xml 一行注册。既有前端文件不在范围。以下为实现 agent 自审，不能称独立审查。

## Confirmed Findings
| 严重度 | 问题与场景 | 证据 | 修复 |
|---|---|---|---|
| 中 | 注册统一限制为绑定后会排除先注册登录再绑定新客 | ReferralPolicyEvaluator.evaluate 的 factStart 分支 | 已按主 agent 核对冻结口径修复：startsAt ≤ registeredAt ≤ boundAt，权威新客 YES 才通过；补前置注册/未来于绑定/未知证据测试 |

## Policy Questions For Independent Review
- 观察期成熟时刻达到 settlementEndsAt 时失败关闭；需核对结算截止政策，不能把尚未确认的运营值当生产已批准。

## Rejected Suspicions
| 疑点 | 核对 |
|---|---|
| 大额减退款溢出 | 非负且退款不大于结算金额后才相减，单测 Long.MAX_VALUE 覆盖 |
| 不同规则字符串键冲突 | 返回结构化 rule + milestoneKey，持久层另加作用域，不直接字符串拼接 |
| 重试因墙上时间跨宽限失效 | 宽限使用首次可信事实接收时间；重放测试跨长时间仍资格相同 |
| 内存候选重复即重复发奖 | 本 API 明确输出候选集合，不签发、发送或持久化奖励；数据库唯一身份仍是后续门禁 |

## Checks Rerun After Fixes
模块测试 13 项通过；git diff --check 通过。2026-09-08 主 agent 转达独立审查最终确认：注册修复正确，13 项报告真实，相关问题关闭，无新增阻断。

## Residual Risks
未验证来源的客户端 bool 不能成为安全证明。持久层必须核对租户/主体/活动关联、证据 revision、风险、版本冻结、时间、配额及唯一身份，不得直接发奖。

## Verdict
pass（仅本纯 SPI 阶段，独立审查确认；不是完整裂变/生产验收）。
