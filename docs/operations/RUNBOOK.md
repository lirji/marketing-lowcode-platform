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

### Kafka 或 Flink

- Kafka 不可用：HTTP 事件接入按 backpressure/503 失败，不伪成功；Decision 使用已加载制品；外部副作用保持本地 outbox 待发。
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
