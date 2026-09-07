# API、事件与兼容策略

机器可读规范位于 `marketing-contracts/src/main/resources`：REST 使用 OpenAPI，异步接口使用 AsyncAPI，图、Manifest 和事件 payload 使用 JSON Schema。本文件定义规范之外的治理规则。

## HTTP 边界

生产请求必须携带 OIDC Bearer JWT。tenant/org/shop/permissions 来自已验证 claims，服务端不得信任 body/query/header 中自报的租户范围。`X-Dev-*` 仅在 `MARKETING_SECURITY_MODE=DEV` 且显式启用时可用；生产 Helm 强制关闭。

控制面命令、在线决策和 PromotionApplication 生命周期使用 `Idempotency-Key`。创建资金账户、人群字段/分群、模板、实验和事件来源也统一使用各领域库的 `mk_api_command`：服务端在同一本地事务中将 key 绑定 tenant、operation、规范化 payload hash 与首次响应，默认提供七天重放窗口并支持跨 Pod/重启重放；过期索引供数据库保留策略分批清理。事件以 `(tenant, sourceId, eventId)`、触达以 `contactKey`、流式副作用以 `commandId` 去重：

- 同 key + 同 payload：返回第一次的 status/body；
- 同 key + 不同 payload：`409 IDEMPOTENCY_PAYLOAD_CONFLICT`；
- 执行中重复：返回可重试冲突/operation handle，不并发执行两次；
- key TTL 不短于业务可重试窗口，资金命令的永久业务 commandId 仍由账本唯一约束保护。

错误使用 `application/problem+json`（RFC 9457），至少包含 `type`、`title`、`status`、`detail`、`instance`、`errorCode`、`traceId` 和字段 violations。不得在 detail 中泄露 token、PII、SQL 或内部堆栈。

### 权益 SKU 目录

`GET /api/v1/benefit-skus` 需要 `benefit:read`。营销只把已验证的货主作为 `X-Tenant-Id` 传给权益中台，并使用独立机器 Bearer Token 认证调用者；不得转发人类会话 Token，也不得省略租户头来列举全部货主。

| HTTP | `code` | 语义 |
|---|---|---|
| 401 | `BENEFIT_CATALOG_AUTH_REQUIRED` | 目录机器 Token 缺失或无效 |
| 403 | `BENEFIT_CATALOG_TENANT_MISMATCH` | 权益中台拒绝委派货主，例如租户头与签名身份不一致 |
| 403 | `BENEFIT_CATALOG_ACCESS_DENIED` | 其他权限不足 |
| 200 `[]` | — | 当前货主没有符合条件的 ACTIVE SKU；不是错误，也不能跨货主补数据 |

## 关键调用链

```text
POST /api/v1/decisions:evaluate
  -> DecisionResponse + signed offerToken + generation/candidates/reasons
POST /api/v1/promotion-applications
  -> token verify + atomic ReservationGroup
POST /api/v1/promotion-applications/{id}/confirm|cancel|refund|reverse
  -> idempotent lifecycle transition + ledger facts
POST /internal/v1/award-intents
  -> server reconstructs SKU/amount from signed OfferToken -> risk gate -> first-result outbox/block
GET /api/v1/award-intents?campaignId=...
  -> tenant-scoped merged delivery/risk-block view with seek pagination
```

控制面 API 覆盖 campaigns、definitions、approvals、compile/artifacts、releases 和 registries；数据面 API 覆盖 audiences、events/quarantine、journeys/enrollments、consents/templates/contacts、measurements/traces/experiments。实际 path、request 和 response 以 OpenAPI 为准。

Journey 在生产配置中采用 `STREAM` 单写模式：外部只能向 `/api/v1/events` 提交 `JOURNEY_SIGNAL`，Flink keyed state 是状态推进者；`/enrollments` 和 `/enrollments/{id}/signals` 只用于 DEV/DIRECT 调试。Decision 与 Journey 都必须先安装经编译器证明、ReleaseManifest 签名的不可变 generation，再接收独立签名且单调递增的 ActivationDirective，预热本身不切流。

## 接入事件信封

HTTP 接入使用下列租户外置的信封；tenant 只从已验证身份上下文取得，不能由 payload 自报。Event Gateway 校验 source 注册、类型、schema version、时间窗、大小、可选 aggregateVersion 连续性，然后在同一数据库事务中持久化 receipt 与 outbox：

```json
{
  "eventId": "globally-unique-event-id",
  "sourceId": "registered-commerce-source",
  "eventType": "JOURNEY_SIGNAL",
  "businessKey": "enrollment-001",
  "subjectToken": "tokenized-subject",
  "occurredAt": "2026-09-02T10:00:00Z",
  "schemaVersion": "1.0.0",
  "aggregateVersion": 1,
  "data": {
    "planReference": {
      "artifactId": "journey-plan-...",
      "generation": 7,
      "activationSequence": 12,
      "journeyId": "welcome-journey",
      "journeyVersion": 3
    },
    "signal": {"type": "START", "attributes": {}}
  }
}
```

机器契约定义 14 个主 Topic：profile change → audience membership、journey signal → journey output → engagement/benefit command、engagement/benefit event、营销应发事实、marketing fact → measurement projection、platform events，以及 release activation/kill switch。确切地址和 payload `$ref` 以 `marketing-contracts/src/main/resources/asyncapi/marketing-events.yaml` 为准。

`marketing.award-expected.v1` 只在租户命中 `CENTER` 且 AwardIntent 通过风控时产生，并与 CENTER outbox 同事务写入。partition key 为 `tenantId:sourceRequestId`，每个权益项携带稳定 `clientItemId`；现金项必须带最小货币单位金额与币种，非现金项不得伪造金额。该事件表达“营销应发”，不代表权益中台已经受理或履约成功。

Topic 命名使用 `mk.<domain>.<fact>.vN`；partition key 首先包含 tenant，再按要求选择 subject/order/enrollment，确保局部有序且避免单 tenant 热键。消费者必须处理重复、乱序、迟到和 schema 不兼容；不可恢复错误经有限重试后进入对应 `.dlt`，outbox 发布永久失败进入 `.dlq`，并保留原坐标、原因与尝试次数。

## Outbox 与副作用

业务状态和 outbox 同本地事务写入；relay 可重复发布。下游以 Kafka topic/partition/offset 或稳定业务 commandId 建立幂等边界。不能将“Kafka exactly-once”外推到短信商、资金系统或另一个数据库。HTTP Provider callback 在解析 JSON 前对原始 body、provider request id 和五分钟时间窗做 HMAC-SHA256 验证，再按 providerEventId 去重；OIDC/生产配置拒绝无签名 callback。

Event Gateway 在 receipt/outbox 入库前按数据库共享视图检查全局 pending、单租户 pending 和最老未发布事件年龄，超过任一阈值返回可重试的 `503 EVENT_OUTBOX_BACKPRESSURE`。pending 由与 outbox 同事务增减的 64 路全局/租户计数维护，检查成本不随历史 outbox 行数增长；短缓存限制每个 Pod 的检查频率。Relay 只在短事务内认领带版本的租约，在事务外等待 Kafka ACK，再用 lease owner/version CAS 回写；Kafka 抖动不会把数据库行锁或连接占用到发送超时。

AwardIntent 的 `Idempotency-Key` 必须等于 `sourceRequestId`。首次请求先持久化带 lease/version fencing 的 `PROCESSING` claim，只有 claim owner 能调用风控；并发请求等待并重放同一首次结果。最终结果只能是 outbox 或 block，首次为 `UNAVAILABLE` 时仍返回 503；同键异请求返回 `409 AWARD_INTENT_IDEMPOTENCY_CONFLICT`。`CHALLENGE`、`REVIEW`、`REJECT` 均以 `RISK_BLOCKED` 返回 202；风控超时、5xx 或降级特征不可用会先持久化 `UNAVAILABLE`，再返回可重试的 `503 RISK_UNAVAILABLE`，重放不得再次调用风控。详细字段和错误响应以 OpenAPI 为准。

## 版本兼容

- URL major 只在无法兼容时升级；同 major 新字段必须可选并有默认语义。
- Event type 的 `.vN` 表示 payload major；consumer 在 producer 切换前必须支持 N 和 N-1。
- 字段不可重用、改变含义或从 optional 改 required；删除先 deprecate、观测无调用后再执行。
- 金额使用 `{currency, minorUnits}` 或明确 decimal scale，禁止 binary float。
- 时间使用带 offset 的 RFC 3339 UTC；业务时区单独字段表达。
- 枚举 consumer 必须对未知值 fail-safe；资金/副作用状态不允许把未知值当成功。

契约变更的最低门禁是 JSON parse/schema、producer/consumer tests、N/N-1 replay、OpenAPI diff 和 AsyncAPI review。`scripts/verify-contracts.sh` 是本地与 CI 的同一入口。

## Replay、DLQ 与隐私

Replay 需要审批号、tenant、topic、event range、目的、速率和 dry-run 结果；默认写到 shadow topic，验证后再进入主处理器。Replay 保留原 eventId，不伪造新业务事实。DLQ payload 的 PII 遵从原数据等级，加密且有 TTL；操作员 UI 默认只显示 tokenized subject 与脱敏摘要。
