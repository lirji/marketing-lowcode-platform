# 首绑切片验证记录

2026-09-08。本报告只覆盖本切片，不代表当前整棵工作树、真实身份/发布来源或生产验收。

## 纯应用与时间验证

- `build-unit-retest.log`：8项通过（Timing 3、Application初始5）。初轮fixture的Mockito重置与公开摘要值碰撞已修复，生产检查未放宽。
- `build-unit-edge.log`：新增3项通过，覆盖反向HMAC排序、最终读取后token到期/撤销、错误Subject绑定和索引锚点。
- `build-unit-nanosecond.log`：新增1项通过，一个用例分别覆盖Subject及Permit的纳秒截止。最终时间使用原始Instant判断，持久化才向下截断至微秒；过期不能被截断重新激活。XML/TXT封存在`evidence/nanosecond-1`。

以上共12个唯一纯场景；没有为边界修复重新启动数据库。后续定向执行会覆盖临时Surefire同名报告，因此不将最后1项XML误报为此前12项全量报告。早期8/3结果以各次独立构建日志为证据。

## 隔离MySQL

`build-db-retest.log`：11项专项通过，0失败/错误/跳过，session 8820退出0。原Surefire XML/TXT与日志封存`evidence/db-11`。独立临时后端快照见`build-workspace.txt`，仅运行ReferralBindingIntegrationTest及必要上游编译，没有运行旧参与/令牌全套。测试数据库由既有Testcontainers fixture创建，不接受外部URL，不连接或清理共享库。

覆盖永久好友唯一归因、12并发/4幂等键、两个邀请人争同好友、同分享token多个好友、角色互斥、自邀/互邀、固定历史版本、条款/租户/Scope冲突、原成功永久重放、新key内容冲突、事务审计Outbox回滚、真实token锁等待后的身份/token/许可到期与已有关系新key回滚、V3表字段中文注释、默认可信许可拒绝及事务外token锁调用拒绝。

初轮`build-db-startup-timeout.log`只有Docker/MySQL初始化超时，未执行业务用例，session97754主动结束退出1。将专用fixture启动预算改为单次300秒并错峰后通过，未放宽断言、未运行共享迁移。

## 生产门禁

Permit中的absolute bindUntil来自测试fixture，尚不代表maxBindAgeSeconds起点已签收。新客、首单、退款、风险、奖励均未由绑定置成功；关系保持BOUND/PENDING_EVIDENCE。真实BFF/JWKS/KMS/JTI、历史签名条款与发布、索引初始化/轮换、隐私保留与物理清理仍待接通和确认。没有启动后台清理、删除数据、执行共享迁移或提交推送。

## 并行依赖边界

本次DB11快照保留开始时的ReferralParticipationService；独立Agent随后对该V1类修复纳秒有效期判断，并另加纯测试，不改变本切片Binding源码。最终源码指纹需分别记录绑定新增/接线代码与依赖快照，不将该后续V1修复误称为DB11覆盖。真实数据库测试不会因此机械重跑；新时间修复由独立纯专项验证。


源码18项与DB通过快照逐一相等，见evidence/source-digests.json；24个旧依赖的快照另列，明确ParticipationService与pom后续变化不属于DB11覆盖。
