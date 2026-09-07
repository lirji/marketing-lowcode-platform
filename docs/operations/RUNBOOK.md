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

```bash
kubectl -n marketing get deploy,pod,hpa,pdb
kubectl -n marketing get events --sort-by=.lastTimestamp
kubectl -n marketing port-forward svc/edge-gateway 18080:8080
curl -fsS http://127.0.0.1:18080/actuator/health/readiness
```

Readiness 表示实例可接新流量，不等于所有异步投影实时。额外验证：当前 desired/loaded generation 一致；Flink 最近 checkpoint 成功；Kafka lag 没有持续增长；Measurement watermark 在 SLO 内；ResourceAccount 对账差异为零。

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
- Decision 使用已加载制品；外部副作用保持本地 outbox 待发。
- Flink restart loop：检查第一个异常、checkpoint 可读性、schema compatibility、transaction timeout 和 DLQ，不连续删除 state。
- 有有效 checkpoint 时从 checkpoint 自动恢复；升级/回滚用 savepoint。只有经批准且能接受状态丢失时才无状态重启。
- 恢复后观察 lag、水位、重复副作用计数和对账，直到追平稳定窗口。

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
