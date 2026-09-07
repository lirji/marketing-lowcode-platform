# SLO、容量与性能准入

## 候选 SLO

这些是 R1 设计目标，不是未压测的承诺。生产 owner 必须根据业务等级批准。

| 能力 | SLI | 建议目标 |
| --- | --- | --- |
| Offer Decision | 服务端成功率；p99（不含调用方网络） | 99.99%；p99 ≤ 30 ms |
| Benefit reserve/confirm | 非业务拒绝成功率；p99 | 99.95%；p99 ≤ 150 ms |
| Control API | 成功率；p95 | 99.9%；p95 ≤ 500 ms |
| Event ingest | 接受成功率；端到 Kafka 延迟 p99 | 99.95%；≤ 1 s |
| Journey action | eligible event 到 command p99 | ≤ 10 s（无 Wait） |
| Audience realtime | profile event 到 membership watermark p99 | ≤ 60 s |
| Measurement | fact 到 dashboard watermark p99 | ≤ 5 min |
| Release propagation | approved manifest 到目标 cell ACK | 99% ≤ 2 min |

可用性用多窗口 burn-rate 告警（5m/1h 快烧、30m/6h 慢烧），延迟同时观察直方图和超阈比例。计划变更在预算耗尽时冻结；安全修复和回滚例外。

## 容量模型

设计 QPS 取合同峰值和预测峰值×重试×突发中的较大者：

```text
designQps = max(contractedQps, forecastQps × retryFactor × burstFactor)
cpuCores = designQps × measuredCpuSecondsPerRequest / targetUtilization
Kafka bytes = eventRate × averageBytes × retentionSeconds × replication × overhead
```

仓库提供确定性计算器：

```bash
python3 scripts/capacity.py \
  --forecast-qps 20000 --contracted-qps 30000 \
  --cpu-ms 2.4 --event-bytes 1400
```

还必须单独估算：tenant/key skew、最大 cart lines/候选数、Redis bitmap、JVM live set、MySQL connections/IOPS、Kafka partitions、Flink state/checkpoint bandwidth、ClickHouse merge、S3 request/egress、渠道并发和遥测放大。

## 性能验收矩阵

固定版本、硬件、dataset seed、租户分布、真实遥测和连接池配置执行：

1. 30 分钟 warm-up；4 小时预计峰值 soak。
2. 5 分钟 3× burst，证明限流/隔舱且无队列失控。
3. 冷启动：无本地缓存加载稳定 Manifest/artifact。
4. 发布风暴：多 generation 预热、灰度和回滚并发。
5. noisy tenant：一个 tenant 高成本规则/事件压满配额，其他 tenant SLO 不恶化。
6. 依赖注入：Redis/MySQL/Kafka/S3/OTel/provider 延迟、断连、限流和半开。
7. Benefit 高争用：热点预算/库存，验证零超发、P99 和公平性。
8. Flink kill/rebalance/checkpoint restore，验证无重复发券/触达。

可重复执行的 API 负载入口：

```bash
# 先运行 scripts/bootstrap.sh，使验收流量激活一份真实签名策略。
docker run --rm --add-host host.docker.internal:host-gateway -i grafana/k6:2.2.0 run \
  -e GATEWAY_URL=http://host.docker.internal:8080 \
  -e RATE=100 -e DURATION=2m -e P99_MS=30 \
  - < tests/performance/decision-smoke.js

docker run --rm --add-host host.docker.internal:host-gateway -i grafana/k6:2.2.0 run \
  -e GATEWAY_URL=http://host.docker.internal:8080 \
  -e RATE=500 -e DURATION=2m -e P99_MS=200 \
  - < tests/performance/event-ingest.js
```

两个脚本分别命中真实的 `POST /decisions:evaluate` 和持久化事件接入，不再以活动列表 GET 代替性能证据。它们仍只是短时准入负载，不等于容量报告。正式报告必须记录 commit/image digest、机器与容器限额、参数、数据规模、预热、结果、失败率、分位数、CPU/内存/GC、Redis、数据库、Kafka lag、Flink checkpoint、资金对账和原始报告链接；生产准入还要执行表中 4 小时 soak、3 倍 burst 与故障场景。

目标环境的一键门禁入口会连续执行混合流量 warm-up、4 小时峰值、3 倍突发和 noisy-tenant 隔离，并把每阶段 k6 summary 留在独立证据目录：

```bash
GATEWAY_URL=https://gateway.preprod.example \
PEAK_DECISION_RATE=100000 PEAK_EVENT_RATE=50000 \
CAPACITY_IMAGE_DIGESTS='edge@sha256:...,decision@sha256:...' \
CAPACITY_DATASET='campaigns=1000,active=200,cart-p99=100' \
CAPACITY_RESOURCE_ENVELOPE='decision=40x4cpu,event=20x4cpu' \
scripts/run-capacity-suite.sh
```

OIDC 环境通过密钥注入设置完整的 `AUTHORIZATION='Bearer …'`，不要把 token 写进报告或 shell history；noisy-tenant 场景分别使用 `HOT_AUTHORIZATION` 与 `CONTROL_AUTHORIZATION` 两个租户身份。DEV header 只用于隔离的非生产压测环境。

Kafka 故障阶段必须显式提供经评审的 `FAULT_DRIVER`（支持 `inject kafka` 和 `recover kafka`）；热点资金阶段必须提供能生成唯一签名 OfferToken 并在结束后执行对账的 `BENEFIT_LOAD_DRIVER`。缺少任一 driver 时，`phase-status.tsv` 会明确记录 `SKIPPED` 且 `suite-status.txt` 为 `INCOMPLETE`；只有所有阶段通过才写 `COMPLETE`。结果按 [容量报告模板](CAPACITY_REPORT_TEMPLATE.md) 补充监控快照、故障时间线和签字，不能只保留 k6 终端摘要。

2026-09-04 在本地 Compose 上以 1 req/s、5 秒和宽松 `P99_MS=5000` 完成负载入口校验：Decision 与 Event Ingest 均为 0% HTTP failure、100% checks，观测 p99 分别为 156.32 ms 和 60.96 ms。该结果只作为脚本与链路可执行证据，未经过预热且不用于判定上表 SLO。

## 扩容与分区

- API 服务按 CPU + 业务 queue/latency 扩容；HPA 只看 CPU/内存不足以保护线程池。
- Kafka partition key 必须包含 tenant 并处理大 tenant shard；扩 partition 会改变 key mapping，先验证状态和 ordering 影响。
- Flink parallelism 调整走 savepoint，maxParallelism/key-group 在首次生产前确定。
- MySQL pool 总连接 = replicas × pool size，必须低于数据库预算并为 migration/admin 留余量。
- ClickHouse 写入批量化；避免高基数小 part。分析降级不能反压 Decision/Benefit。
