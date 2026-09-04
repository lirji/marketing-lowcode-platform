# Codex 后端任务：控制台剩余缺口

前端已按「演示可假、live 不装真」改完。下面接口**没有或不够**，请只改后端 / OpenAPI / 集成测试，不要改 `frontend/`。

约定：

- 保持现有成功路径兼容；新增字段用可选或默认值。
- 所有写操作继续要求 `Idempotency-Key`，失败返回 Problem Details。
- Jackson 字段名与现有 record 一致；时间用 ISO-8601。
- 同步 `marketing-contracts/src/main/resources/openapi/marketing-api.yaml` 和 `ContractSpecificationsTest.API_PATHS`。
- 前端会在 live 模式调用这些路径；404 会被当成「尚无数据」而不是崩溃。

## P0：设计器回读 + 审核驳回

### 1. 读取定义（含 graph）

现状：只有 `POST /api/v1/definitions`。控制台打开活动后无法从服务端恢复画布。

请新增：

```
GET /api/v1/definitions/latest?campaignId={id}&dialect={OFFER_DECISION_DAG|JOURNEY_STATE_MACHINE|...}
GET /api/v1/definitions/{definitionId}/versions/{version}
```

权限：`definition:read`。无数据 404。

响应（与现有 `DefinitionView` 对齐，并带上 graph）：

```json
{
  "definitionId": "offer-CMP-1",
  "campaignId": "CMP-1",
  "version": 3,
  "dialect": "OFFER_DECISION_DAG",
  "semanticHash": "...",
  "status": "DRAFT",
  "createdBy": "actor",
  "graph": {
    "definitionId": "offer-CMP-1",
    "dialect": "OFFER_DECISION_DAG",
    "dialectVersion": "1.0.0",
    "nodes": [{ "id": "n1", "stableTypeId": "offer.condition", "semanticVersion": "1.0.0", "config": {} }],
    "edges": [{ "id": "e1", "sourceNodeId": "n1", "sourcePort": "true", "targetNodeId": "n2", "targetPort": "in" }],
    "variables": {},
    "annotations": { "title": "...", "terms": "..." }
  }
}
```

`latest` 取该 campaign + dialect 最大 `version_no`。

### 2. 审核驳回

现状：`POST /api/v1/approvals/{caseId}/decisions` body 只有 `{ "role": "BUSINESS" }`，服务端一律 `approve()`。`ApprovalCase.Status.REJECTED` 已存在但写不进去。

请兼容扩展：

```json
{ "role": "BUSINESS", "decision": "APPROVE" | "REJECT", "comment": "optional" }
```

- 缺省 `decision` = `APPROVE`（旧客户端不破）。
- `REJECT` 将 case 置 `REJECTED`，定义回到可编辑状态（建议 `DRAFT` 或 `REJECTED`），写入审计。
- 提交者不能审自己的单；权限仍是 `approval:{role}`。
- `comment` 写入审计即可，不要进业务不变量。

## P1：运营列表（控制台已按 ID 点查）

已有、前端已接：

| 用途 | 方法 | 权限 |
| --- | --- | --- |
| 轨迹 | `GET /api/v1/traces/requests/{requestId}`、`GET /api/v1/traces/orders/{orderId}` | `trace:read` |
| 报名 | `GET /api/v1/enrollments/{enrollmentId}` | `journey:read` |
| 触达 | `GET /api/v1/contacts/{contactKey}` | `contact:read` |
| 隔离区 | `GET /api/v1/quarantine`、`POST /api/v1/quarantine/{id}:replay` | `event:read` / `event:replay` |
| 对账 | `GET /api/v1/funding/reconciliation` | 现有 |

仍缺列表（没有就无法做队列页）：

```
GET /api/v1/enrollments?status=&journeyId=&limit=50
GET /api/v1/contacts?state=&limit=50
```

`TraceView` 现在是摘要（candidates/pricing JSON），控制台只能展示字段，没有事件时间线。若有 `mk_decision_trace` 逐步事件，请在 `TraceView` 增加可选：

```json
"events": [{ "at": "ISO", "type": "Decision", "title": "...", "detail": "..." }]
```

缺省为空数组，前端继续用摘要。

## P1：资产 / Audience / 权益 / 模板 / 字段

已有写入或点查，没有运营列表：

```
GET /api/v1/audiences
GET /api/v1/fields
GET /api/v1/templates
GET /api/v1/funding/accounts
```

Audience 预览 `POST ...:preview` 已存在，但控制台没有 `segmentId`。请让 `GET /api/v1/audiences` 返回 `segmentId/version/name/status`。保存快照继续走现有 snapshot API。

权益策略（Benefit Editor）目前没有独立定义读写；若短期不做完整模型，至少：

```
GET /api/v1/benefits
GET /api/v1/benefits/{benefitId}
PUT /api/v1/benefits/{benefitId}
```

DMN 表同理：要么纳入 `GraphDefinition` dialect，要么单独 CRUD。没有接口前，前端 live 只保留本地草稿、不声称已写入。

节点注册表 `GET /api/v1/registries/nodes` 前端已接。

## P2：衡量时间序列与导出

`GET /api/v1/measurements/dashboard` 只有 KPI + `counts`（类型合计），没有小时/日序列。总览脉搏图和分析趋势图因此不能装真。

请新增（或扩展 dashboard）：

```
GET /api/v1/measurements/series?from=&to=&granularity=hour|day
```

```json
{ "points": [{ "at": "ISO", "revenueMinor": 0, "costMinor": 0, "conversions": 0 }] }
```

以及：

- 漏斗 / 触点贡献：从现有 attribution 投影聚合，或明确 501。
- `POST /api/v1/measurements/attribution:recompute`（权限 `measurement:ingest`）。
- 报表导出可后置。

## P2：其它控制台按钮

| UI | 建议 |
| --- | --- |
| 营销日历 | 活动上已有 `createdAt`；若要日历，补 `GET /api/v1/campaigns?from=&to=` 或 schedule 字段 |
| 敏感字段工单 | 独立审批 + 短时 token，不要在现网 trace 里回明文 |
| 风险策略 | 审核门禁配置 CRUD |
| 租户切换 | 身份侧多租户会话，不是 console 改 JWT |

## 不要做

- 不要改 `frontend/`。
- 不要让未知 JSON 字段把旧 `approve` 变成 reject。
- 不要在 DEV 以外信任 `X-Dev-*`。
- 不要为了控制台把 fail-closed 改成 fail-open。
