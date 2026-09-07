# 容量与故障验证报告模板

> 本模板和 `scripts/run-capacity-suite.sh` 只定义可复现门禁。只有目标环境的原始结果、监控快照、故障时间线和责任人签字齐全，才能把结论标为 PASS。

## 结论

- 测试日期、环境、负责人：
- Git commit / 镜像 digest：
- 结论：`PASS / FAIL / INCOMPLETE`
- 未执行或被豁免场景（必须写原因、风险接受人和到期日）：

## 固定输入

| 项目 | 值 |
| --- | --- |
| 集群/区域/AZ | |
| Pod 副本、request/limit、JVM 参数 | |
| MySQL 规格、连接池、IOPS | |
| Redis 规格/拓扑 | |
| Kafka broker/partition/副本 | |
| 数据集规模、候选数、cart lines、tenant/key skew | |
| 遥测采样率与 exporter | |
| Decision 峰值 QPS / Event 峰值 QPS | |

## 阶段结果

| 阶段 | 时长/负载 | 成功率 | p50/p95/p99 | dropped | 饱和点 | 结论 | 原始证据 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 30m warm-up | | | | | | | |
| 4h peak soak | | | | | | | |
| 5m 3× burst | | | | | | | |
| noisy tenant | | | | | | | |
| Kafka fault | | | | | | | |
| 热点预算/库存 | | | | | | | |

自动化证据目录至少应包含 `run-metadata.json`、`phase-status.tsv`、`suite-status.txt`
和各阶段 `*-summary.json`；`suite-status.txt` 不是 `COMPLETE` 时不得给出 PASS。

饱和点至少记录 CPU、内存/GC、线程池队列、Hikari pending、MySQL lock/IO、Redis latency、Kafka lag、outbox pending/oldest age、Flink checkpoint 和业务 p99。

## 故障注入时间线

| 时间 | 动作 | 预期保护 | 实际响应 | 恢复时间/RTO | 数据丢失或重复 | 证据 |
| --- | --- | --- | --- | --- | --- | --- |
| | Kafka 不可用/恢复 | outbox 有界堆积并在阈值后 503；恢复后排空 | | | | |
| | Redis 不可用/恢复 | Gateway 集群限流 fail-closed，不绕过租户配额 | | | | |
| | MySQL 延迟/断连/恢复 | 超时、熔断和池等待受控 | | | | |
| | Pod kill/滚动发布 | readiness 只接入已加载 generation | | | | |
| | Flink kill/rebalance/restore | checkpoint 恢复且无重复外部效果 | | | | |

## 守恒与恢复核验

- Benefit 对账：`authorized = available + reserved + consumed`，违规数：
- 热点分桶总和与总账差异：
- Kafka 恢复后 outbox pending/oldest age 回到基线的时间：
- DLQ/quarantine 数量及逐项处置：
- Decision 冷启动和发布风暴期间 `decisionRuntime` readiness 变化：

## 审批

| 角色 | 姓名 | 结论 | 日期 |
| --- | --- | --- | --- |
| 服务 Owner | | | |
| SRE | | | |
| DBA / Kafka Owner | | | |
| 资金风控 Owner | | | |
