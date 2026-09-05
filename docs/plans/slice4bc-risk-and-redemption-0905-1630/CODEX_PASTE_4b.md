# Codex 粘贴稿 — Slice 4b 发前风控（只这一刀）

把本文件全文交给 Codex。**不要做 4c**（wallet freeze/redeem/refund）。4c 等本 PR 合入后再单独开。

对照计划：`docs/plans/slice4bc-risk-and-redemption-0905-1630/`  
父总则：`docs/plans/benefit-marketing-alignment-0905-0949/BACKEND_HANDOFF.md` §0。

失败 RFC 9457（`code` + `message`/`detail`）。金额 `long`，禁止 `double`。写操作继续 `Idempotency-Key`。

---

## 仓库

| 仓库 | 本刀 |
|---|---|
| `/Users/liruijun/personal/LLM/marketing-lowcode-platform` | Assembler 后、outbox 前 risk check；拒绝读模型；OpenAPI |
| `/Users/liruijun/personal/LLM/risk-platform` | 只登记 `sourceId=MARKETING_AWARD` 规则绑定 / seed。**禁止**改 `RiskRequest` 的 channel/bizType/amount 校验 |
| benefit-center / workflow / recon / drools / 任何 `frontend/` `*-console/` | **不改** |

`mvn verify` 相关模块要绿。做完把「给 Cursor 的字段表」贴回（文末模板）。

---

## 禁止

- 改任何 console / `frontend/`。
- 业务拦截或不可用时仍写 `mk_award_intent_outbox`。
- risk 超时 / 5xx / 降级 **fail-open** 放行。
- 改 `ck_award_intent_status`（继续 `PENDING|SENDING|SENT|DEAD`）。
- 自造 `channel=MARKETING` / `bizType=AWARD` / `amount=0`（现网会 400）。
- 只打日志、不落可查询读模型。
- 只拦 CENTER、放过 LEGACY/SHADOW。
- 做 4a SKU 审批、超预算 BPMN、4c 核销。
- 给营销加「提交 AwardIntent」表单（那是前端，而且本刀禁止）。

---

## 现网锚点（不要猜）

营销：

- `AwardIntentService.create`：`services/benefit-funding-service/.../AwardIntentService.java`
- 现顺序：幂等查 **只** `mk_award_intent_outbox` → LEGACY 跳过 assemble → insert outbox → CENTER 才写 expected facts
- `POST /internal/v1/award-intents` 方法上 `@ResponseStatus(ACCEPTED)`；503 不能靠这个注解，要抛异常走 `ApiExceptionHandler`
- `GET /api/v1/award-intents?campaignId=` 只读 outbox，seek 用 `created_at + intent_id`
- OpenAPI：`marketing-contracts/.../marketing-api.yaml`  
  `AwardIntentView.status` 现仅 `PENDING|SENT|DEAD`  
  `deliveryResult` **required** enum（无空值）—— RISK_BLOCKED 行必须改契约，见下
- `DependencyUnavailableException` 现映射 **502**。`RISK_UNAVAILABLE` 要 **503**：按 `exception.code()` 分支，不要把所有依赖失败改成 503

risk-platform：

- `POST /api/v1/risk/evaluations`
- `RiskRequest`：`channel ∈ MOBILE|WEB|ATM|API|BRANCH`；`bizType ∈ TRANSFER|REMITTANCE|PAYMENT|WITHDRAWAL`；`amount @Positive`（≥1）
- 引擎降级返回 `action=CHALLENGE` 且 hit `DEGRADED_FEATURE_UNAVAILABLE`（不是 HTTP 5xx）
- `sourceId + txnId` 是 risk 侧幂等键

---

## 调用点与映射

在 `create()`：

1. `Idempotency-Key` 必须等于 `sourceRequestId`（现状保留）。
2. **先查** outbox **和** `mk_award_intent_block`（`tenantId + sourceSystem + sourceRequestId`）。命中则重放首次结果，**不再**调 risk。  
   - 重放出箱 → 202 + 原视图  
   - 重放业务拦截 → 202 + `RISK_BLOCKED`  
   - 重放 `UNAVAILABLE` → **仍 503**（可重试）  
   - requestHash 不一致 → 现有 `AWARD_INTENT_IDEMPOTENCY_CONFLICT`
3. `assemble()`：CENTER/SHADOW 照旧。LEGACY 仍可不组装 payload，但 **也要过 risk**（金额用哨兵，见下）。
4. `POST {risk}/api/v1/risk/evaluations`（服务账号 `risk.evaluate`；独立超时 + circuit + bulkhead，与发奖线程池隔离）：

```
sourceId  = MARKETING_AWARD
txnId     = sourceRequestId
channel   = API
bizType   = PAYMENT
accountNo = subjectRef          # 内部；读模型仍只暴露 subjectHash
amount    = 组装后 CASH 合计最小单位（≥1）；无 CASH / LEGACY = 1
currency  = 有现金则币种，否则 XXX
eventTime = Clock.instant()
```

5. 决策：

| 条件 | outbox | expected facts | block | HTTP |
|---|---|---|---|---|
| ALLOW | 写（现状 CENTER/SHADOW/LEGACY） | CENTER 照旧 | 无 | 202 + PENDING/SENT 视图 |
| 业务 REJECT / CHALLENGE / REVIEW | **不写** | **不写** | 写，`riskAction` 保留引擎值 | **202** + `RISK_BLOCKED` |
| 超时 / 5xx | **不写** | **不写** | 写 `UNAVAILABLE` | **503** `RISK_UNAVAILABLE` `retryable=true` |
| `CHALLENGE` + hit `DEGRADED_FEATURE_UNAVAILABLE` | **不写** | **不写** | 写 `UNAVAILABLE` | **503** 同上 |

CENTER / SHADOW / LEGACY **都过** check。

---

## 存储与列表

新表 `mk_award_intent_block`（Flyway）。至少：

```
tenant_id, intent_id, source_system, source_request_id, campaign_id,
definition_version, subject_hash, delivery_mode, request_hash,
risk_action, risk_reason, risk_decision_id,
created_at, updated_at
```

- `intent_id` 新建 UUID，给列表 cursor 用（与 outbox 同一套 seek：`created_at desc, intent_id desc`）
- 唯一键：`(tenant_id, source_system, source_request_id)`
- **不要**改 `ck_award_intent_status`

`GET /api/v1/award-intents`：**UNION** outbox ∪ block，同一 `campaignId` + 同一 seek。cursor 指向上一页任意一侧的 `intentId`；找不到则保持现网「cursor invalid」。

并发双 create：最多一条 outbox **或** 一条 block。

---

## OpenAPI（先合契约再写实现）

`AwardIntentView`：

```
status: PENDING | SENT | DEAD | RISK_BLOCKED
riskAction?: CHALLENGE | REVIEW | REJECT | UNAVAILABLE   # 仅 RISK_BLOCKED
riskReason?: string
riskDecisionId?: string | null
deliveryResult: 改为 nullable；RISK_BLOCKED 必须 null
```

`RISK_BLOCKED`：`benefitOrderNo=null`，`sentAt=null`，`attempts=0`，`lastError=""`（原因只放 `riskReason`，别和 DEAD 的 `lastError` 混用）。

`POST /internal/v1/award-intents` 增加：

```
202  出箱或业务拦截（含重放）
503  RISK_UNAVAILABLE（含 UNAVAILABLE 重放）
```

同步 `ContractSpecificationsTest`。

---

## risk-platform（最小）

- 给 `sourceId=MARKETING_AWARD` 绑一条默认可 ALLOW 的规则（或沿用现网默认绑定），README 写清 seed/admin 步骤。
- 测试至少 ALLOW + REJECT 各一条能打到该 sourceId。
- **不要**扩展 channel/bizType/amount 校验。

---

## 测试（营销）

- ALLOW → 有 outbox；CENTER relay 仍 202。
- REJECT → 无 outbox 行、无 expected facts；GET 列表可见 `RISK_BLOCKED`；create 返回 202。
- 非 CASH / LEGACY `amount=1` 能过 risk 校验（不是 400）。
- 同 `sourceRequestId` 再触发：不二次 check、不双写。
- risk 超时 / 降级 CHALLENGE：无 outbox；create 503；重放仍 503。
- 并发双 create：最多一条 outbox 或一条 block。
- LEGACY / SHADOW REJECT 也被拦。
- Slice 2 幂等 / `AWARD_IDEMPOTENCY_KEY_MISMATCH` / DEAD relay 回归。
- 列表 UNION + cursor 跨表（一页里既有 PENDING 又有 RISK_BLOCKED）。

---

## 做完贴回 Cursor（照填）

```
POST /internal/v1/award-intents
  ALLOW / 重放出箱     → 202 AwardIntentView (PENDING|SENT|DEAD)
  业务拦截 / 重放拦截   → 202 AwardIntentView (RISK_BLOCKED)
  不可用 / 重放不可用   → 503 code=RISK_UNAVAILABLE retryable=true

GET /api/v1/award-intents?campaignId=
  status += RISK_BLOCKED
  riskAction, riskReason, riskDecisionId
  deliveryResult nullable
  UNION 表：mk_award_intent_outbox + mk_award_intent_block
  seek: created_at + intent_id

错误码：RISK_UNAVAILABLE（不要用 INTERNAL_ERROR）
```
