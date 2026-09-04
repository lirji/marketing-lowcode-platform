# Delivery Status

## Goal

从零交付一套参考大型零售电商复杂度的 Java 21、DDD、微服务营销低代码平台完整 R1；同一产品范围覆盖策略设计、受众、优惠决策、权益资金、事件旅程、触达、实验衡量、审计合规和生产运维，不把核心能力延期到后续版本。

## State

- Phase: WP-11 — complete
- Status: implementation-complete / production-candidate / conditional-go
- Last updated: 2026-09-04 Asia/Taipei
- Implementation delivered: WP-00 至 WP-11 全部完成

## Delivered

- Java 21 Maven reactor、React 19/TypeScript 控制台、九个领域服务、Edge Gateway 和三个 Flink 实时作业已经落地。
- 五种低代码方言共享版本信封、图校验、语义哈希和编译 ABI；OFFER/JOURNEY 具备可执行制品，Drools/DMN 适配器与精确定价求解器已实现。
- Campaign/Definition/Approval/Release 全控制面已持久化，包含多角色审批、条款冻结、Ed25519 制品与发布签名、generation/activation sequence 单调保护、运行时 ACK、回滚和 kill switch。
- Audience/Event/Decision/Benefit/Journey/Engagement/Measurement 业务闭环已经贯通；资金库存采用 fencing、双账本守恒、毒消息隔离和对账，外部触达采用业务幂等和补偿语义。
- Kafka 明确建 Topic 且关闭自动创建；Flink 作业采用 keyed state、processing-time timer、checkpoint 与 Kafka Exactly-Once Sink，三个作业均在真实 Compose 集群完成 checkpoint。
- OIDC/DEV 身份边界、租户组织门店范围、RFC 9457、OpenAPI/AsyncAPI/JSON Schema、审计链、Prometheus/OTel/Tempo/Loki/Grafana、Docker Compose、Helm、CI、安全扫描与 SBOM 已提供。
- 完整黑盒验收覆盖低代码编排、双人审批、签名发布、受众投影、报价、权益结算、旅程触达、归因和最终资金对账，2026-09-04 实测通过。

## Verification Evidence

| Check | Result | Evidence |
| --- | --- | --- |
| `./scripts/verify.sh --full` | pass | 28 个 Maven 模块、架构测试、前端 lint/unit/build、4 个 Playwright E2E、真实 MySQL 8.4 migration、Compose/Helm/Prometheus 门禁 |
| `./scripts/verify-deployment.sh` | pass | Compose schema + semantic hardening、Helm 默认/生产/Flink render、11 条 Prometheus 规则、JSON 和 shell 语法 |
| Flink live deployment | pass | audience、journey、measurement 三个 Job 均为 `RUNNING`；最终复核分别已有 37、37、36 个成功 checkpoint |
| `./scripts/smoke.sh` | pass | 首轮 run `f6519dc34f67`；应用最终配置并重建服务后复验 run `9196d5d977f0`；真实 Gateway/Kafka/Flink/MySQL/Redis 全链路均为 `PASSED` |
| runtime configuration | pass | 10 个 Java 服务均以 `MANAGEMENT_OTLP_METRICS_EXPORT_ENABLED=false` 运行，Prometheus 负责指标采集，容器重建后全部恢复健康 |
| decision k6 wiring check | pass | k6 2.2.0，1 req/s × 5s，0% HTTP failure，检查 100%，本机 p99 156.32 ms |
| event ingest k6 wiring check | pass | k6 2.2.0，1 req/s × 5s，0% HTTP failure，检查 100%，本机 p99 60.96 ms |

低速 k6 结果只证明负载入口和观测链可运行，不是容量或 SLO 证明；正式生产仍需在目标硬件、目标数据规模和真实依赖上完成预热、峰值、突发、长稳、故障和灾备测试。

## Decisions And Deviations

- “一期全出”是一个完整产品范围和一个最终发布；内部 WP 只表达依赖顺序，不形成对外半成品版本。
- 同步优惠使用纯函数 `OFFER_DECISION_DAG`，异步旅程使用有状态 `JOURNEY_STATE_MACHINE`；两者共享类型和治理，不共享执行语义。
- 采用平衡型领域部署，不做分布式 CRUD；Decision 只报价，Benefit/Funding 独占库存、预算、券与资金状态机。
- Flink 2.2 的 DataStream V2 当前不能构建带 post-commit topology 的 Kafka Exactly-Once Sink，因此 R1 作业使用仍受支持的 DataStream API，保留稳定 UID、checkpoint、keyed state/timer 和两阶段提交语义。
- Compose 是可复现的单机验证拓扑；生产 Helm 强制 OIDC、非 root、只读文件系统、PDB/HPA、外部密钥和托管高可用数据设施。

## Conditional Production Gates

- 真实峰值、租户/热点 key 分布、购物车和候选规模、资金风险上限及上下游 SLA 需要由业务 owner 提供并压测校准。
- 必须完成 4 小时峰值 soak、3 倍突发、noisy-tenant、Redis/MySQL/Kafka/S3/provider 故障注入、Flink savepoint/restore 和跨可用区灾备演练。
- 必须在目标环境执行 CodeQL、Trivy、gitleaks、SBOM/镜像签名验证，并接入企业 Vault/KMS、External Secrets、审计归档和告警通知。
- 数据驻留、保留期、未成年人、同意、营销法规、预算权限和财务对账阈值需安全、法务、风控和财务签字。

满足以上组织与环境门禁后，才可将状态从 `conditional-go` 提升为生产上线批准；仓库不声称已经达到京东实际流量规模。
