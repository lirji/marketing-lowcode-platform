# R3 本地激活与熔断交付

2026-09-09。用户批准的本地机制已实现：签名指令校验、单调序号持久化、回滚到留存历史制品、熔断及新鲜期限、并发/故障/独立 JVM 恢复验证。未开放真实发布或参与入口。

## 验收覆盖

| 验收 | 实现 | 验证 |
| --- | --- | --- |
| R3-A 指令信任 | ReferralRuntimeDirectiveVerifier；manifest/激活/熔断签名、slot/版本/时间绑定 | 21 个真实签名专项及数据库拒绝不写场景 |
| R3-B 单调状态 | V13 cursor+audit；锁读、幂等/冲突、追加与 CAS 同事务 | 并发同内容、异内容、高低序号竞争、故障回滚 |
| R3-C 回滚 | 更高 activationSequence 选择 V12 已保存、仍可信的历史制品；仍需 READY 证明 | 代次 2 → 1，序号保持递增；旧序号拒绝 |
| R3-D 熔断 | KILL 按 tenant/namespace；启用持续拒绝，clear 从签名时间起算 ≤10 秒，重放不续期 | 过期边界、缺状态、长期暂停、重复 clear、新指令恢复本地检查 |
| R3-E 默认门禁 | READY Port unavailable 拒绝激活；LocalGuard 只代表本地检查 | 缺证明、错绑定、过期/超长证明拒绝；本地通过仍无参与许可 |
| 恢复与损坏 | 当前公钥重验保存指令、清单和制品；不信任缓存或数据库原文 | 独立 JVM 恢复序号 9/代次 1；签名损坏拒绝 |

## 文件与验证

新增一个 SPI verifier、RuntimeStateService/ReadinessPort/Repository、MyBatis 实现与 XML、V13 两表、21 项签名测试、20 项数据库测试及测试 classpath 的恢复探针。所有表/字段有中文注释。

`TESTCONTAINERS_RYUK_DISABLED=true ./mvnw -o -q -pl services/referral-service -am verify` 成功：SPI 93 项、referral 服务 226 项，零失败/错误/跳过。新增 21+20 已包含其中，不重复计数。生产 jar 包含状态服务、不含测试探针。完整命令和源码指纹见 r3-evidence.json。

自审补强校验器与仓储配置不一致的拒绝，相关测试通过。架构检查结果见 R3_QA_REPORT.md。沿用 CI 全 reactor clean verify 与 Surefire 报告收集，无重复 job；未触发远程 CI。

## 剩余边界

- 没有新增 HTTP、自动启用 Bean 或生产签名配置。ReadinessPort 没有生产实现，测试夹具不等于 READY/ACK。
- 独立 JVM 探针验证落库事实可跨进程重验；不是常驻消费者的崩溃接管或完整生产启动验收。
- LocalGuard 是时点检查结果，不能直接作为参与许可；仍需活动/主体权威映射、真实 READY/ACK、参与事务内 epoch 栅栏。
- 现有 KillSwitchDirective 没有健康水位字段，持续开放必须接可信健康协议；重复消费时间不是健康证明。
- control 发布、真实闭包和 R2 READY/ACK、完整参与许可接线仍未完成，默认拒绝保留。

未推送、未合并 main、未部署、未执行共享库迁移。V13 只在隔离 MySQL 验证。回滚应用时保留游标/审计事实，不清表、不降低序号。前端和其他既有改动保留。
