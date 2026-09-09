> 本文件记录 R1/R2 历史切片；最新 R3 本地机制见 [R3_DELIVERY_REPORT.md](R3_DELIVERY_REPORT.md)。

# 裂变可信制品与耐久安装 QA

2026-09-09；本地 Java 21、离线 Maven、专属 MySQL 8.4.11 Testcontainers。未访问共享演示库或真实签名服务。

## 测试结果

| 场景 | 证据 | 结果 |
| --- | --- | --- |
| 真实 Ed25519 清单及制品、篡改/错钥/tenant/slot/ABI/版本/摘要 | ReferralReleaseVerifierTest（42 项，含严格解析场景） | pass |
| 未审批引用、错误时间、未支持灰度/附加制品 | 同上；只证明本地格式/签名拒绝，非权威审批闭包 | pass |
| 签名载荷未知/重复/缺失字段、null、数字强转/溢出、非法规则 | 同上 18 种签名非法载荷；不可变快照检查 | pass |
| 耐久恢复、同键重放、冲突拒绝、插入后回滚、锁后纳秒过期 | ReferralReleaseInstallationMySqlTest | pass |
| 同代次并发同内容与异内容、tenant/权限/范围隔离、默认参与拒绝 | 同上；专项最终 10 项 | pass |
| V12 表/每列注释、真实 Flyway 全新库迁移 | 同上 | pass |
| SPI 完整回归 | 72 项，0 失败/错误/跳过 | pass |
| referral 服务完整回归 | 206 项，0 失败/错误/跳过 | pass |
| 架构入口 | ArchitectureRulesTest 命令成功，JUnit 报告 2 项 | pass |

完整命令和模块结果见 evidence.json；日志位于对应 /tmp 文件，代码指纹已保存。

## 缺陷与修复

最初数据库测试使用 WebEnvironment.NONE，而平台安全自动配置需要 HttpSecurity；8 项上下文错误未执行业务断言。改用既有 RANDOM_PORT 模式，专项 8 项及最终扩展到 10 项全部通过；无业务代码绕过安全配置。

## 未验证

真实闭包、READY ACK、激活/回滚 epoch、熔断水位、参与许可接线、control 发布及生产部署未完成。恢复测试是重建应用对象并读取数据库重验，不能宣称多进程重启/故障转移验收。

结论：R1 与 R2 VERIFIED 切片 pass；完整发布 conditional-pass，仅表示已实现部分通过，剩余验收被外部协议依赖阻塞。
