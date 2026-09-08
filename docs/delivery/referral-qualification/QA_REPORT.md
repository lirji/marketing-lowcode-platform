# V4A事实账本验证

2026-09-08。范围仅V4A事实账本，共30个唯一场景；不是全平台或真实渠道/生产验收。

- 准备边界9纯项：精确历史/CAS、索引锚点/Scope漂移、历史不可用、原始纳秒、显式来源寿命。evidence/prepare-9。
- Intake应用10个唯一纯项：初轮9通过，随后隔离同digest反例新增1，修复后定向2通过（其中1复跑）。日志/原定向XML分别在evidence/intake-initial-9与intake-quarantine-2；初9同名XML已被定向运行替换，保留其原构建日志，不伪造完整XML。
- 隔离MySQL11：root独立测试类，80699退出0，11项0失败/错误/跳过；V1–V4顺序执行于新容器，真实AES-GCM/HMAC测试保护、完整历史、永久隔离、原回执、并发、真实锚点锁等待到期、原子Outbox回滚、NO PAD业务键及中文注释均通过。evidence/db-11/result.json列19项源码/依赖逐一与测试快照相等。

独立审查发现并关闭：Inbox冲突隔离不能依赖businessDigest是否变化，现按原AAD Header的quarantined状态决定持久变更。反例返回相同digest但隔离Header=true，仍要求replace、fanout及commit后Conflict。

测试保护密钥仅fixture，纯应用内存保护替身不算密码学证据，真实KMS/JWKS/来源映射仍默认拒绝。V4只留下fanout目标水位，未执行真实关系投影、有效人数、奖励或清理；这些属于下一V4B及后续门禁。完整范围、唯一场景计数与纯测试源码补充见source-evidence.json。后续新增V4B纯类不在本30项或19源码范围内。

## V4B/V5 专项增量

- 计数纯测试 8 个唯一场景：首次/观察等待/退款与恢复/重复状态/Scope/损坏计数/溢出/多关系；evidence/v4b-count-8。
- 资格纯应用 18 个唯一场景：正常资格、纳秒观察截止、缺许可撤当前保历史、来源异常净化、最后 task 锁时过期、新请求 fence、current 水位重读、会员 asOf、固定制品、跨主体 Scope、退款、锚点/真实权限、保存后到期回滚、会员旧修订、同修订冲突、粘性隔离、注册目标和注册首次接收锚；evidence/v5-application-18，92275 退出 0，零失败/跳过。
- 共 26 唯一纯场景，不与 V4A 的 30 累加冒称当前模块总覆盖。静态 DDL 检查 3 表 51 字段中文注释、task 无父行 FK；真实 V5 MySQL 与 fanout 联合验证尚待 root 专项。
- 失败过程原始日志保留：最初隔离源码快照缺父 pom 声明的两个目录；fixture 密文小于保护合同 16 字节；Mockito 重设 stub 时误执行事务断言；新增字段时测试调用参数未同步。这些均已修正，未把失败轮次算通过证据。独立生产审查额外发现的保存后过期及会员水位问题已修复并由新增用例覆盖。
