# 生产运行手册

## 值班原则

先保护消费者与资金安全，再恢复吞吐。事故中禁止直接改数据库余额、手工伪造成功回执或跳过 OfferToken/Manifest 验签。所有操作记录 incident id、操作者、时间、tenant/cell、命令和验证证据；涉及个人信息时仅使用 tokenized identifier。

严重级别：

| Severity | 示例 | 响应目标 |
| --- | --- | --- |
| SEV-1 | 资金超发、跨租户泄露、错误价格大面积生效、双 region 写入 | 5 分钟响应，立即收紧 kill switch/流量隔离 |
| SEV-2 | Decision 大面积不可用、Flink 无 checkpoint、渠道重复发送风险 | 15 分钟响应，30 分钟内确定缓解路径 |
| SEV-3 | 单租户延迟、分析水位落后、少量 DLQ | 工作时段处理并给出恢复 ETA |

## 首五分钟

1. 宣告事件并冻结非必要发布、迁移、回放和批任务。
2. 确认 blast radius：environment、cell、tenant、generation、API/event、开始时间。
3. 查 Grafana 的 request rate/error/latency、Benefit invariant、Kafka lag、Flink checkpoint、水位和 OTel drop。
4. 用 requestId/orderId/enrollmentId 查脱敏 trace；不要使用姓名/手机号搜索日志。
5. 选择可逆缓解：暂停受影响 campaign、将 canary 权重归零、切回保留 generation、停止 connector consumer 或收紧 kill switch。
6. 保存 dashboard、Manifest digest、deployment revision、topic offset/checkpoint 和对账快照。

## 健康判断

触达恢复：V5 之前的 `PENDING` / `RETRY_PENDING` 若没有请求指纹，重发返回
`CONTACT_RETRY_UNVERIFIABLE`。先按租户、contactKey、providerRequestId 核查渠道实际结果；
不要清空记录、修改 contactKey 绕过频控或用当前请求反填历史指纹。已知终态走已有回执/
结果查询；无法核实投递结果时保留待核查状态。新增请求的原始业务字段不可变，变化会返回
`IDEMPOTENCY_PAYLOAD_CONFLICT`。V5 只添加可空摘要列，旧行不虚构补齐，旧二进制仍可读写；
灰度期间仍写无指纹记录的旧实例必须先升级，再恢复自动重试。本文不提供强制发奖/发短信绕过。

```bash
kubectl -n marketing get deploy,pod,hpa,pdb
kubectl -n marketing get events --sort-by=.lastTimestamp
kubectl -n marketing port-forward svc/edge-gateway 18080:8080
curl -fsS http://127.0.0.1:18080/actuator/health/readiness
```

Readiness 表示实例可接新流量，不等于所有异步投影实时。额外验证：当前 desired/loaded generation 一致；Flink 最近 checkpoint 成功；Kafka lag 没有持续增长；Measurement watermark 在 SLO 内；ResourceAccount 对账差异为零。

本地空库启动时，Decision 没有合法 generation，readiness DOWN 是预期状态。网关不再以此阻断控制面启动；`smoke.sh --wait` 等待 Decision liveness 后，通过真实审批、编译、验签、ACK 和激活建立规则。范围优惠的金额预览必须返回 `OFFER_PREVIEW_CONTEXT_REQUIRED`，后续完整购物车 Decision 请求才验证资格与价格。验收使用 `ACCEPTANCE_TENANT_ID` / `ACCEPTANCE_ORGANIZATION_ID` / `ACCEPTANCE_SHOP_ID` 指定隔离范围，会实际写入验收记录。

该租户必须已有权益中台治理通过的 ACTIVE SKU，建议用 `ACCEPTANCE_BENEFIT_SKU_ID` 明确选择。验收先查目录，再登记本项目自己的 ACTIVE BenefitDefinition，并用 `benefitId@version` 发布；目录为空或不可达会在新增验收记录前失败，不自动创建或激活外部 SKU。权益中台默认地址为 `BENEFIT_CENTER_BASE_URL`；只启动目录并关闭 workers/relay/consumer 只能证明目录读取，不能证明真实发奖闭环。

网关 HTTP 读取默认 12 秒，CircuitBreaker 的 TimeLimiter 单独设预算：普通控制操作 4 秒、编译 15 秒、Decision/Journey 各自的运行时控制路由 10 秒、在线 Decision 和事件接入仍为 1 秒。配置分别为 `GATEWAY_RESPONSE_TIMEOUT` / `GATEWAY_CONTROL_TIMEOUT` / `GATEWAY_COMPILER_TIMEOUT` / `GATEWAY_RUNTIME_TIMEOUT` / `GATEWAY_DECISION_TIMEOUT` / `GATEWAY_EVENT_TIMEOUT`。编译器 DRL 内部上限为 10 秒；扩大环境预算应核对排队与连接预算，不能仅更改外层。框架的两个超时机制不同，参考 [Spring Cloud CircuitBreaker 官方配置说明](https://docs.spring.io/spring-cloud-circuitbreaker/reference/spring-cloud-circuitbreaker-resilience4j/circuit-breaker-properties-configuration.html)。

验收请求只有只读、业务幂等键或已核实内容寻址幂等的编译操作才对临时传输错误重试，最多三次且保持原始请求字节。504 不代表服务端回滚。日志中的 `ACCEPTANCE_RUN_ID` 用于查找业务记录；固定该 ID **不保证整段脚本可任意阶段重跑**，含动态时间的写入应先核实已提交结果及幂等指纹。

### 本地 Flink 提交与恢复

普通 Compose 启动不包含 `bootstrap` profile 的提交器；使用 `./scripts/submit-flink-jobs.sh`。脚本仅在集群没有运行作业且状态卷不存在 `_metadata` 时首次提交。三个指定作业必须各一个、所有子任务 RUNNING、最近完成 checkpoint 不超过 180 秒。作业缺失、名称冲突、重复、子任务未就绪或 checkpoint 陈旧均不会触发盲目重提。

已有作业历史或 checkpoint/savepoint 时，先确认兼容制品和各作业对应的元数据路径，再提供恢复计划：

```json
{
  "audience": "file:///opt/flink/checkpoints/<audience-job-id>/chk-<n>/_metadata",
  "journey": "file:///opt/flink/checkpoints/<journey-job-id>/chk-<n>/_metadata",
  "measurement": "file:///opt/flink/checkpoints/<measurement-job-id>/chk-<n>/_metadata"
}
```

替换占位符为状态卷中真实且互不相同的路径，执行 `./scripts/submit-flink-jobs.sh --restore-plan /absolute/path/restore.json`。脚本核对三份元数据存在，以 `--claimMode no_claim` 恢复，保留源检查点的所有权，并等待新完成检查点；不会取消现有作业，也不会自动挑选最新目录。部分提交失败时应查实际作业状态并处理，不能直接重试提交剩余或删除状态。

本机入口以 `.flink-submit.lock/` 互斥；异常退出遗留锁时先核对进程和 Flink 作业，再清理失效锁。该锁不提供跨主机互斥，只允许单一运维入口操作同一集群。恢复模式的本地路径契约不适用于远程对象存储；生产升级另按 savepoint 与兼容验证流程执行。

## 常见故障

### Decision 延迟或错误升高

- 按 tenant、cell、generation、artifact digest 分解，不要先全局扩容掩盖错误制品。
- 若只影响新 generation：停止推进，canary=0，回滚到已验签保留 generation。
- 若 CPU 饱和且结果正确：确认 HPA、pod pending、GC、候选组合规模与 noisy tenant；对高成本租户限流。
- S3/Control 故障时，已加载 stable 应继续服务；若实例尝试每请求远程读取，这是架构缺陷，应摘除实例。
- 不允许通过关闭签名、金额守恒或租户校验换取可用性。

### Benefit/Funding 对账差异

- 立即冻结相关 resource account/campaign 的新 reserve；confirm/refund 是否继续由事故指挥根据账本证据决定。
- 对比 aggregate version、不可变 ledger、outbox、订单 commandId 和 provider receipt；先判断投影延迟还是权威账本破坏。
- 用 reconciliation repair command 生成成对流水，不直接 `UPDATE balance`。
- 差异恢复为零且独立复核后才解冻；保留完整审计与消费者影响清单。

### AwardIntent 风控阻断或投递积压

- 先按 tenant、campaignId、sourceRequestId 区分 `RISK_BLOCKED`、`PENDING` 与 `DEAD`。`GET /api/v1/award-intents` 已合并 outbox/block 读模型，不要只查日志判断是否发过奖。
- `REJECT/CHALLENGE/REVIEW` 是业务阻断，不能手工改成 `PENDING`；`UNAVAILABLE` 表示风控调用不可证明可用，修复 risk-platform、凭据或网络后仍需走受审计的新业务请求，不能删除首次结果后原键重试。
- CENTER `PENDING` 积压先检查 `MARKETING_AWARD_RELAY_ENABLED`、权益中台健康、Bearer 权限与租约超时配置。HTTP 400/409/422 或达到最大尝试会进入 `DEAD`；当前没有无审计的批量清空/重发捷径。
- 回切时先把受影响租户从 `MARKETING_AWARD_TENANT_MODES` 的 CENTER 映射移除，阻止新意图进入中台，再关闭 relay。既有 PENDING/DEAD 行和 `marketing.award-expected.v1` 事实保留给对账与人工收敛。

### Kafka 或 Flink

- Kafka 不可用：观察 `marketing_outbox_pending{outbox="events"}`、`marketing_outbox_oldest_age_seconds{outbox="events"}` 和 `marketing_outbox_backpressure_active{outbox="events"}`；HTTP 接入在全局/单租户深度或最老年龄达到阈值后返回 `503 EVENT_OUTBOX_BACKPRESSURE`，阈值内已成功提交 receipt/outbox 的请求仍返回 202。不要通过抬高阈值把数据库磁盘或连接池耗尽。
- Kafka 恢复后确认过期 lease 被重新认领、pending 与 oldest age 持续下降、DLQ 没有异常增长，再逐步放开入口；Relay 等待 ACK 的阶段不应持有 `mk_event_outbox` 行锁或数据库连接。
- 若 oldest age 很大但 publish_attempts/lease_version 一直为零，核对 publish_state 与 published_at/dead_lettered_at 是否矛盾。旧演示种子曾写入已发布时间却遗漏终态，导致展示行被背压计作积压。先确认仅限三种 `mock-outbox-*` 演示记录及计数未增加，再修正元数据；不能通过删除真实消息或提高背压阈值处理。
- Decision 使用已加载制品；外部副作用保持本地 outbox 待发。
- Flink restart loop：检查第一个异常、checkpoint 可读性、schema compatibility、transaction timeout 和 DLQ，不连续删除 state。
- 有有效 checkpoint 时从 checkpoint 自动恢复；升级/回滚用 savepoint。只有经批准且能接受状态丢失时才无状态重启。
- 恢复后观察 lag、水位、重复副作用计数和对账，直到追平稳定窗口。

Journey 的 `JOURNEY_RUNTIME_STORE_UNAVAILABLE` 表示数据库连接/查询或初始化配置故障。事件和定时器均将该错误抛给作业恢复机制，不能转成业务死信后推进 checkpoint，也不消耗业务定时器的重试次数。修复数据库/配置后从有效 checkpoint 恢复；非法输入继续走死信。数据库读取使用同一项目版本的 MyBatis 3.5.19 和 Mapper XML，保持历史激活记录验签及实例版本绑定。

| Journey 作业参数 | 默认 | 允许范围 |
| --- | --- | --- |
| `JOURNEY_DB_CONNECT_TIMEOUT_MS` | 2000 ms | 1–30000 ms |
| `JOURNEY_DB_SOCKET_TIMEOUT_MS` | 3000 ms | 1–30000 ms |
| `JOURNEY_DB_QUERY_TIMEOUT_SECONDS` | 2 s | 1–30 s |

连接串中的 connectTimeout/socketTimeout 会被上述有界设置覆盖，不能用 0 关闭保护。这里是单次等待上限，作业重启/退避策略仍由 Flink 部署配置管理；本轮没有验证真实集群 checkpoint 恢复时长。

### Journey 组合执行探针投递（代码已接入，运行环境尚未启用）

探针用于证明指定 Flink 作业各子任务实际支持组合协议；它不创建旅程、不发奖、不切换活动。当前运行环境仍未部署此能力，禁止为了验证探针重提已有三个作业。可信目标 job ID、实际提交 JAR SHA256、并行度和参与公钥 ID 必须由受控部署证据提供，不能从回执反推预期值。

- 登记 `POST /api/v1/journey-runtime/execution-probes` 只保存签名挑战；显式 `POST /api/v1/journey-runtime/execution-probes/{campaignId}/{probeId}:dispatch` 才创建投递任务。两者都要求 `runtime:warm`、可信租户及原组织/店铺范围。重复派发返回原进度，不重置终态；没有新挑战时不得用改表方式重新开始。
- 查询原执行证据用 `GET .../{campaignId}/{probeId}`，查询持久投递进度用 `GET .../{campaignId}/{probeId}/dispatch`。`SENT` 只代表全部有界消息获 broker 确认，`COVERED` 只代表当时校验的实际子任务集合完整；两者都不是活动 `APPLIED`，后续应用仍须重验当前部署、信任、时间及风险门禁。完整集合后来到达时，已 `SENT` 的投递记录仍保留 `SENT`，执行证据查询反映最新覆盖情况。
- `JOURNEY_PROBE_DELIVERY_ENABLED=false` 为安全默认；打开前需具备完整 `JOURNEY_EXECUTION_TARGET_*`、Control/编译公钥和专用 `JOURNEY_EXECUTION_ACK_TRUSTED_*` 配置，并独立批准部署。`JOURNEY_INPUT_TOPIC` 固定服务端发送目标；目标改变后旧任务拒绝继续，不能静默转投。`JOURNEY_PROBE_DELIVERY_RELAY_ENABLED=false` 可暂停后台领取而保留派发/查询，重启后按原租约恢复；两个开关均为启动配置，不是即时取消已发送消息。
- 回执仍从既有 `marketing.journey.output-topic` 消费，需要原 `MARKETING_KAFKA_CONSUMERS_ENABLED` 和 Kafka 连接/安全配置。Web 服务只持有执行公钥；执行私钥仅属于实际 Flink 作业。缺回执时先检查可信目标、作业日志、checkpoint 和消费者积压；不得用 Web 安装 ACK、消息条数或人工测试签名代替真实子任务证据。
- V6 `mk_journey_probe_delivery` 属于 Journey 本库，以原挑战全作用域为主键/外键，保存下一 nonce、失败计数与 30 秒租约代次。每次最多 16 条，固定总量为 16 倍并行度（上限 2048）；未知发送结果不推进游标。领取/证据复核/进度提交分属短事务，Kafka 在事务外发送。旧执行者迟到提交不会覆盖新进度。
- `RETRY` 使用有界指数退避和抖动，连续失败最多 5 次；原挑战到期转 `EXPIRED`，校验失败或耗尽预算转 `DEAD`。进程退出或提交结果未知时等待原租约到期并查询持久状态；恢复可能重复发送同一 nonce，数据库按实际 subtask 唯一回执去重。不得直接写 `SENT`、清空失败次数或延长签名有效期。
- 独立 producer 每次 send 最多阻塞 100ms，一批确认最多等 2 秒，底层 delivery timeout 5 秒、request timeout 3 秒，缓冲 1MiB；专用单线程 relay 每次只处理一批，固定延迟 250ms，停机最多等待 20 秒。预算按每个启用实例计算，共享 DB/多副本容量与租户公平性仍需 S13 实测，不能将单次故障测试当作容量验收。
- 观察 `marketing.journey.probe.delivery{result}`、`marketing.journey.probe.relay.failure`、`journey-probe-delivery` executor 指标和 `marketing.journey.probe.relay` trace。查询返回稳定 `lastErrorCode`，不返回目标 topic、租约令牌或内部异常。告警出现 `DEAD/EXPIRED` 或持续 relay failure 时核对签名期限、部署配置、数据库及 broker，再通过新签名挑战重试；历史任务不自动删除，保留与归档政策待 S13 签收。

隔离验证使用真实 MySQL 8.4.11 与 apache/kafka:3.8.0（与已核对的 dev_infra broker 版本一致），应用当前 Kafka client 4.1.1。Testcontainers Kafka 沿用项目管理的 2.0.3，无版本升级。实际 Flink/Kafka checkpoint 端到端、生产 savepoint、混合版本退出与生产容量仍是独立未完成验收。

### 决策侧人群缓存

数据库 `mk_audience_membership_projection` 仍为权威。启动不再全表扫描，查询使用 `(tenant_id,audience_id,subject_hash)` 主键；本地缓存默认最多 10000 个正/负结果，最长缓存一秒，跨副本撤销最多受该缓存窗口影响（另计事件投递和查询耗时）。`marketing.audience-projection.max-cache-entries` 控制容量，`marketing.audience-projection.cache-max-age-ms` 可在 1–1000 ms 内缩短缓存；原 reconcile-interval 轮询参数不再使用。

过期缓存遇到数据库错误不会返回旧成员资格。当前 JDBC 决策幂等事务使用 READ_COMMITTED，成员 SELECT 清除 MyBatis 会话缓存，避免把旧事务快照当成新资格回填；幂等行仍由唯一键和 FOR UPDATE 保护。高基数流量会增加主键回源量，应按实际数据库容量检查连接池等待、决策延迟和错误率；不能只增大缓存期限掩盖撤销延迟。未对十倍流量给出性能承诺。

### OIDC/Key rotation

- 新登录/refresh 失败但已签 token 仍可验证时，保持短期服务并监控 token expiry。
- JWK unknown kid：刷新受控 JWK cache；不可临时关闭验签或接受任意 audience/issuer。
- 轮换至少保留旧验证 key 超过最大 token/OfferToken TTL + 时钟偏差；签名 key 与加密 key 分开轮换。

### Provider 429/5xx/timeout

- 遵守 provider `Retry-After`，按 connector/tenant 隔舱；429 不快速重试。
- timeout 为 UNKNOWN，先按 idempotency key 查询/等待回执，再决定重试。
- 超过预算进入 DLQ，保留原 contact/effect key。人工 replay 也不能更换业务幂等键。

## 本地统一链路追踪

六个本地平台共用同级 `dev-infra` 的 OpenTelemetry Collector、Tempo、Prometheus、Loki 和 Grafana。启动营销平台完整观测档：

```bash
cd ../dev-infra
make marketing-obs
cd ../marketing-lowcode-platform
./scripts/bootstrap.sh --observability
```

Grafana 地址为 `http://127.0.0.1:3001`。在 Explore 选择 Tempo，可按 `resource.service.name`、HTTP route、错误状态或 32 位 traceId 查询；打开 span 后可跳转到 Loki 日志，Service Graph 展示同步 HTTP/Kafka 调用关系。默认本地全采样，资源不足时把 `TRACING_SAMPLE_PROBABILITY` 和 `DECISION_TRACING_SAMPLE_PROBABILITY` 调低；紧急关闭可设置 `OTEL_SDK_DISABLED=true`。

同步 HTTP 与直接 Kafka producer/consumer 通过 W3C `tracecontext,baggage` 传播。数据库 outbox 的 relay 在原事务提交后异步运行，自动埋点会从新 trace 开始；envelope 中的业务 `traceId` 仅作为日志、审计和对账检索键，不能声称是一条跨数小时的连续 Tempo trace。完整接入、脱敏和排障规则见同级 `dev-infra/docs/observability.md`。

## 本地故障演练

以下脚本只允许本地 Compose，具有显式 guard：

```bash
CONFIRM_GAME_DAY=marketing-r1-local ./scripts/game-day.sh decision-restart
CONFIRM_GAME_DAY=marketing-r1-local ./scripts/game-day.sh telemetry-loss
CONFIRM_GAME_DAY=marketing-r1-local ./scripts/game-day.sh kafka-restart
```

生产 game day 必须有变更单、观察员、终止阈值、业务低峰窗口、恢复 owner 和对账步骤，不能直接复用本地 kill 命令。

## 事故关闭

恢复后至少观察一个业务峰值或 60 分钟（取更长），确认错误率/尾延迟、资金守恒、Flink checkpoint/lag、水位、DLQ 和重复副作用均稳定。24 小时内形成时间线；SEV-1/2 做无责复盘，行动项需有 owner、截止日和可验证测试。

### Journey 探针的 checkpoint 隔离验收

在可用的 Docker 引擎上运行 `./scripts/verify-journey-checkpoint.sh`。脚本打包当前 Journey 作业，使用 Testcontainers 的独立网络、MySQL、Kafka 和 Flink 会话。测试经 Journey 服务登记并派发探针，在 Kafka 未提交读下观察到两个子任务的回执，再触发该作业的 checkpoint。服务的持久查询须从 PENDING（0 份）变为 READY（2 份），业务激活和派发表应为空。测试结束后释放本次容器。运行耗时包括镜像拉取及 Maven 构建。
