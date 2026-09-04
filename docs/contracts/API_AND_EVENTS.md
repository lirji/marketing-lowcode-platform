# API、事件与兼容策略

机器可读规范位于 `marketing-contracts/src/main/resources`：REST 使用 OpenAPI，异步接口使用 AsyncAPI，图、Manifest 和事件 payload 使用 JSON Schema。本文件定义规范之外的治理规则。

## HTTP 边界

生产请求必须携带 OIDC Bearer JWT。tenant/org/shop/permissions 来自已验证 claims，服务端不得信任 body/query/header 中自报的租户范围。`X-Dev-*` 仅在 `MARKETING_SECURITY_MODE=DEV` 且显式启用时可用；生产 Helm 强制关闭。

控制面命令、在线决策和 PromotionApplication 生命周期使用 `Idempotency-Key`。服务端将 key 绑定 tenant、operation、规范化 payload hash 与原始响应；事件以 `(tenant, sourceId, eventId)`、触达以 `contactKey`、流式副作用以 `commandId` 去重：

- 同 key + 同 payload：返回第一次的 status/body；
- 同 key + 不同 payload：`409 IDEMPOTENCY_KEY_REUSED`；
- 执行中重复：返回可重试冲突/operation handle，不并发执行两次；
- key TTL 不短于业务可重试窗口，资金命令的永久业务 commandId 仍由账本唯一约束保护。

错误使用 `application/problem+json`（RFC 9457），至少包含 `type`、`title`、`status`、`detail`、`instance`、`errorCode`、`traceId` 和字段 violations。不得在 detail 中泄露 token、PII、SQL 或内部堆栈。

## 关键调用链

```text
POST /api/v1/decisions:evaluate
  -> DecisionResponse + signed offerToken + generation/candidates/reasons
POST /api/v1/promotion-applications
  -> token verify + atomic ReservationGroup
POST /api/v1/promotion-applications/{id}/confirm|cancel|refund|reverse
  -> idempotent lifecycle transition + ledger facts
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

机器契约定义 13 个主 Topic：profile change → audience membership、journey signal → journey output → engagement/benefit command、engagement/benefit event、marketing fact → measurement projection、platform events，以及 release activation/kill switch。确切地址和 payload `$ref` 以 `marketing-contracts/src/main/resources/asyncapi/marketing-events.yaml` 为准。

Topic 命名使用 `mk.<domain>.<fact>.vN`；partition key 首先包含 tenant，再按要求选择 subject/order/enrollment，确保局部有序且避免单 tenant 热键。消费者必须处理重复、乱序、迟到和 schema 不兼容；不可恢复错误经有限重试后进入对应 `.dlt`，outbox 发布永久失败进入 `.dlq`，并保留原坐标、原因与尝试次数。

## Outbox 与副作用

业务状态和 outbox 同本地事务写入；relay 可重复发布。下游以 Kafka topic/partition/offset 或稳定业务 commandId 建立幂等边界。不能将“Kafka exactly-once”外推到短信商、资金系统或另一个数据库。HTTP Provider callback 在解析 JSON 前对原始 body、provider request id 和五分钟时间窗做 HMAC-SHA256 验证，再按 providerEventId 去重；OIDC/生产配置拒绝无签名 callback。

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
