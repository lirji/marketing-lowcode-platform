# 数据保护、恢复与区域灾备

## 数据分级与权威性

| 数据 | Authority | 建议 RPO / RTO | 恢复方法 |
| --- | --- | --- | --- |
| Control definitions/approvals/releases | MySQL + versioned S3 | ≤5 min / ≤30 min | PITR + manifest/artifact version verify |
| Benefit/Funding ledger | MySQL | 0–1 min / ≤15 min | synchronous/semisync design + PITR + invariant check |
| Audience metadata/snapshot | MySQL + S3 | ≤15 min / ≤60 min | restore metadata + rebuild Redis bitmap |
| Journey runtime state | Flink checkpoint/savepoint + Kafka | ≤1 min / ≤30 min | restore compatible checkpoint and replay |
| Raw facts/measurement | Kafka + S3 | ≤5 min / ≤60 min query | replay to rebuild ClickHouse |
| Redis/ClickHouse projections | 非权威 | 可重建 | warm/rebuild from authority |

最终数值由业务连续性负责人批准。对象存储启用 versioning、跨区域复制、object lock（按合规要求）和独立账号备份；数据库 PITR 与备份账号隔离生产写账号。

## 备份要求

- MySQL：每日全量 + binlog/PITR，Benefit 更高保护等级；备份加密、不可变、跨账号/region。
- S3：Artifact/Manifest 使用 content digest 和签名验证；checkpoint bucket 有生命周期但不得早于最大恢复窗口。
- Kafka：不是唯一备份。关键 raw facts 落 S3；记录 topic config、schema 和 consumer offset。
- ClickHouse/Redis：按恢复成本决定 snapshot；任何 snapshot 恢复后仍从事实校验/补齐。
- Keycloak/IdP：realm/client/key/claim mapping 进入受控 IaC 与 IdP 自身备份。

备份成功不等于可恢复。至少每月自动还原到隔离环境，每季度做业务级恢复和对账。

## 本地恢复演示

```bash
./scripts/backup.sh
CONFIRM_RESTORE=20260902T100000Z ./scripts/restore.sh 20260902T100000Z
```

脚本校验 SHA-256、停止写服务、通过 `dev-infra` 恢复 MySQL/ClickHouse 并跑 smoke。它只覆盖本地开发拓扑，不替代生产 PITR、S3 replication、Kafka/Flink state 或 KMS 恢复。

## Region failover

正常态只有一个 Benefit/Funding 写 region。流量管理、数据库 writer lease 和 fencing epoch 三者共同防 split-brain；失联 region 默认失去写资格，不能靠 DNS 单独切写。

1. 宣告 DR，冻结发布/replay/migration，记录最后已确认 writer epoch。
2. 判断主 region 是否 fenced；若不能证明，Benefit 新 reserve 保持关闭。
3. 恢复/提升目标 MySQL writer，校验 GTID/PITR point 和 ledger invariant。
4. 切换 S3 replicated artifact endpoint，验证 Manifest/Artifact 签名和 generation。
5. Kafka consumer 从记录 offset 恢复；Flink 从兼容 checkpoint/savepoint 启动作业。
6. 先开只读/Decision，小流量开 Event/Journey；Benefit 在 fencing 和对账全绿后开。
7. 重建 Redis/ClickHouse projection，watermark 追平后开放分析。
8. 保存实际 RPO/RTO、丢失/重复范围和人工决策，禁止自动 failback。

## 恢复验收

- 所有数据库 migration/version、tenant 行数和 checksums 符合预期；
- ReleaseManifest 与 artifact 签名/digest/ABI 可验证；
- Benefit 账户守恒、订单 command 唯一、outbox 与 ledger 无缺口；
- Flink checkpoint 成功且 effect idempotency 无重复；
- Kafka lag 与 Measurement watermark 追平；
- 权限、密钥、审计和数据保留仍有效；
- 业务 owner 明确接受 RPO 内的数据差异后才关闭 DR。
