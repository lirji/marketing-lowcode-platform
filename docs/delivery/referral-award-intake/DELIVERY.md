# V11 耐久 HELD 受理阶段进度

## 范围

新增专用应用编排、默认拒绝端口及 V11 context/HELD outbox/expected-fact。不修改旧 Drools 入口、旧发送表或旧 Relay SQL；无 HTTP、真实确认客户端和自动发送。永久 prepare 与固定上下文先提交，确认/恢复在事务外，本地回执与受理引用、HELD、expected-fact 一起提交。原远端成功、本地失败保留 CONFIRMING/UNKNOWN 恢复身份，不调用 abandon。

## 当前验证

初次纯组合 23 项（Intake 9 + Assembler 9 + Snapshot 5）通过，原日志 maven-pure.txt。独立初审发现非 ALLOW 风险结果锁后时限和最后写入后的 lease 时限缺口，已最小修复；修后 Intake 11 项通过，日志 maven-pure-time-fix-final.txt。两轮重叠，不能相加称 34 项；当前独立纯用例共 25，其中时限变更重跑 11。

maven-pure-time-fix.txt 是源码同步路径错误后误运行旧快照的日志，不能作为新修复证据；已保留，不计入结论。DB 类已随最终纯测试 test-compile。MySQL 8 场景已一次通过，maven-mysql.txt：2026-09-08 12:16:36 +08，退出 0，BUILD SUCCESS，8 项零失败/错误/跳过；V1–V11 迁移成功。测试使用单次临时 MySQL 8.4.11，完全没有外部数据库 URL。当前独立用例 25 纯 + 8 DB = 33；仅此阶段，不是整平台全量通过。

## 审查与剩余门禁

方案预审通过；源码初审两处时限问题已独立复核关闭；最终独立审查 PASS：16 文件 SHA 与实际快照逐项一致，33 项原 XML 均零失败/错误/跳过，MySQL V1–V11 与 BUILD SUCCESS 已核；无本阶段剩余阻断。V10 原 21 项证据保持历史原样，本轮新增仓储 lock 由 V11 专项覆盖。V10 测试迁移断言改为核对版本 1–10，不因合法追加 V11 而错误失败，未重跑旧 8 DB 场景。

真实身份/风险/确认/密文保护端口默认拒绝，CENTER 开关默认关闭。后续仍需真实在线 confirm 来源验证、取消事件持续同步、投递前取消/资格复核、HELD 放行及发送恢复；本片仅本地耐久 HELD，不等于实际权益履约或生产验收。真实参数、渠道、密钥与隐私留存方案仍待确认。

## MySQL 实证

8 个方法覆盖：V11 原子 HELD/expected 并验证旧 Relay 扫描不到；过期 token 原成功唯一回放；远端确认后本地故障 SQL 整体回滚并同身份恢复；取消水位耐久且低水位不能清除；同水位刷新时间重放而永久回执冲突隔离已 HELD 行；风险不可用后重新签名保持原密文与固定上下文；最后写入超过 proof 截止回滚；另一真实事务锁住 preparation，跨纳秒 confirmation 截止后请求只能领域拒绝，保持 CONFIRMING 与零 expected。新应用未注册 HTTP/worker。

原始报告在 reports/，source-sha256.txt 覆盖 16 个本阶段文件（含修改的已有新 referral assembler、prep lock 和 V10 测试兼容断言），与 snapshot-path.txt 指向的隔离源码逐项一致。
