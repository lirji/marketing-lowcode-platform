# R3 本地机制代码审查

2026-09-09，同一 Agent 在实现后自审，无独立评审声明。范围：DirectiveVerifier、RuntimeStateService、ReadinessPort、Repository/Mapper/XML、V13、专项与独立 JVM 探针。

## 已修复/复核

| 级别 | 场景 | 证据及处理 |
| --- | --- | --- |
| 中 | 显式装配时校验器 namespace 与仓储槽位配置不一致，可能把有效指令存到不匹配槽位 | RuntimeStateService.activationSlot/applyKill/inspect 同时核对服务配置，加入不写库测试 |
| 中 | READY 查询/行锁等待跨过有效期，事务仍推进状态 | activate 锁后重新验证指令与证明原始 Instant；等待过期测试断言 cursor/audit 都不提交 |
| 中 | 重复 clear 熔断消息按本地接收时间延长开放窗口 | clearUntil 只从签名 activatedAt 起算；同序号重放不写入，10 秒边界测试 |
| 中 | 恢复时直接相信已持久化 JSON | inspect 重验清单、原制品和指令签名；损坏签名拒绝，独立 JVM 用当前公钥验证数据库原文 |

除第一项在自审时补强外，其余为实现内已有防御的场景复核，不声称修复了线上漏洞。

## 并发与边界复核

- cursor 按流唯一保留后 FOR UPDATE；低序号拒绝，同序号比较完整指令，不同内容冲突。只有更高序号能追加审计并 CAS 游标，失败整体回滚。
- 回滚目标必须 V12 已保存、当前验签/时间有效，并有受信 READY 证明和更高 activationSequence；不是减少 generation 后重用旧序号。
- KILL 按现有 tenant/namespace 合同隔离，不擅自增加没有被签名保护的 env/cell 范围。启用熔断无自动恢复，不依赖 READY。
- LocalGuard 是时点本地检查结果，存在读后状态变化；不是参与许可，后续参与仍需活动映射、身份和事务内 epoch 栅栏。当前默认许可 Port 未接入本类。
- Ready Port 是内部可信适配边界，无生产实现、默认 unavailable；测试证明不能由 HTTP 构造。未移除 control 拒绝。
- 事务外执行制品解析与外部 READY 查询，锁内只进行有界指令/证明复验和数据库变更。

## 结论

本地切片自审 pass；SPI 93、服务 226 回归通过。生产 READY/ACK、完整熔断健康水位、活动映射及用户许可不在本地切片的完成声明内。
