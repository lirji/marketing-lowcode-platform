# 后端执行说明 — Slice 4b / 4c

Cursor **不改** Java / OpenAPI / 迁移。一次只做一刀：先 4b，再 4c。

| 仓库 | 4b | 4c |
|---|---|---|
| `/Users/liruijun/personal/LLM/marketing-lowcode-platform` | Assembler 后、outbox 前 risk check；拒绝读模型 | **不改**（只消费事实，本刀无新 consumer 也可） |
| `/Users/liruijun/personal/LLM/risk-platform` | 登记 `sourceId=MARKETING_AWARD` 规则；**不要**改 `RiskRequest` 枚举/amount 校验 | 不改 |
| `/Users/liruijun/personal/LLM/benefit-center` | 不改 | wallet 三动作 + ledger + fact |
| workflow / recon / drools | 不改 | 不改 |

失败 RFC 9457。写操作 `Idempotency-Key`。金额 `long`。禁止 `double`。

三高总则仍遵守父文件  
`docs/plans/benefit-marketing-alignment-0905-0949/BACKEND_HANDOFF.md` §0。

---

## 0. 禁止

- 改任何 `frontend/` / `*-console/`。
- 拒绝时仍写 `mk_award_intent_outbox`。
- risk 不可用 fail-open 放行。
- 核销按**当前**模板重算面额。
- 用 Remediation `REVERSE` 冒充 `:refund`。
- 做 4a 已交付的 SKU 审批、超预算 BPMN。

---

## Slice 4b — 发前 check

### 1. 调用点

`AwardIntentService.create`：`assemble()` 成功之后、`insert outbox` 之前。

**先查** `mk_award_intent_outbox` 与 `mk_award_intent_block`（同 `tenantId+sourceRequestId`）。命中则返回首次结果，**不再**调 risk。

`RiskRequest` 现网校验（`RiskRequest.java` / `docs/api/openapi.yaml`）禁止 `channel=MARKETING`、`bizType=AWARD`、`amount=0`。4b **映射到已有枚举**，不要改 risk-platform 校验：

```
POST {risk}/api/v1/risk/evaluations
  sourceId  = MARKETING_AWARD           # 自由串；risk 侧登记规则绑定
  txnId     = sourceRequestId
  channel   = API                       # 仅 MOBILE|WEB|ATM|API|BRANCH
  bizType   = PAYMENT                   # 仅 TRANSFER|REMITTANCE|PAYMENT|WITHDRAWAL
  accountNo = subjectRef
  amount    = CASH 合计最小单位（≥1）；非 CASH = 1（@Positive，禁止 0）
  currency  = 有现金则币种，否则 XXX
  eventTime = Clock.instant()
```

risk 登记：给 `sourceId=MARKETING_AWARD` 绑一条默认可 ALLOW 的规则（或沿用现网默认），并在 seed/admin 步骤写进 README。测试至少覆盖 ALLOW 与 REJECT 各一条。

权限：服务账号 `risk.evaluate`。Circuit breaker + 明确超时。Bulkhead 与发奖线程池隔离。

CENTER / SHADOW / LEGACY **都过** check。不要只拦 CENTER。

### 2. 决策与 HTTP

| 条件 | outbox | block | HTTP |
|---|---|---|---|
| ALLOW | 写（现状） | 无 | 202 + PENDING 视图 |
| 业务 REJECT / CHALLENGE / REVIEW | **不写** | 写，`riskAction` 保留引擎值 | **202** + `RISK_BLOCKED` 视图 |
| 超时 / 5xx | **不写** | 写 `UNAVAILABLE` | **503** `RISK_UNAVAILABLE` |
| 降级 `CHALLENGE` + hit `DEGRADED_FEATURE_UNAVAILABLE` | **不写** | 写 `UNAVAILABLE` | **503** `RISK_UNAVAILABLE` |

不要改 `ck_award_intent_status`（仍 `PENDING|SENDING|SENT|DEAD`）。

### 3. 读模型（给 Cursor）

新表 `mk_award_intent_block`（至少 `tenant_id, campaign_id, source_request_id, risk_action, risk_reason, risk_decision_id, created_at` + 与列表 seek 对齐的排序键）。

`GET /api/v1/award-intents?campaignId=` **UNION** outbox 与 block，同一 seek cursor。

```
status: PENDING | SENT | DEAD | RISK_BLOCKED
riskAction?: CHALLENGE | REVIEW | REJECT | UNAVAILABLE   # 仅 RISK_BLOCKED
riskReason?: string
riskDecisionId?: string
```

`RISK_BLOCKED` 无 `benefitOrderNo`；`deliveryResult` 空。禁止只打日志。

### 4. 测试

- ALLOW → 有 outbox，relay 仍 202。
- REJECT → 无 outbox；GET 可见 RISK_BLOCKED；create 返回 202。
- 非 CASH amount=1 能过 risk 校验（不是 400）。
- 同 sourceRequestId 再触发：不二次 check、不双写。
- risk 超时 / 降级 CHALLENGE：无 outbox，create 503。
- 并发双 create：最多一条 outbox 或一条 block。
- LEGACY/SHADOW 也被拦。
- Slice 2 幂等 / DEAD relay 回归。

通知 Cursor：新 status、字段、202 vs 503、错误码。

---

## Slice 4c — 冻 / 核 / 退

等 4b 合入后再开。

### 1. OpenAPI

```
POST /openapi/v1/wallet-entries/{entryId}:freeze
POST /openapi/v1/wallet-entries/{entryId}:redeem
POST /openapi/v1/wallet-entries/{entryId}:refund
  Idempotency-Key
  body: { reason?: string, merchantRef?: string, expectedVersion?: int64 }
  202 WalletEntryCommandAcceptance { entryId, status, version }
```

权限：现网可先 `benefit.admin` 或已有 award write；**不要**先拆导致 console 进不去。文档写清。

### 2. 状态机与存储

现网 `bc_wallet_entry` / `WalletEntry` / `WalletEntryView` **没有** `version`。本刀迁移加 `version BIGINT NOT NULL DEFAULT 0`，OpenAPI 同步。请求体用 `expectedVersion`，**不要** `expectedStatus`。

```
UNUSED → FROZEN → USED
UNUSED → USED
USED   → :refund → REVERSED
EXPIRED / REVERSED / FROZEN→UNUSED → 禁止（本刀无 unfreeze）
```

金额 / skuVersion **只读 entry 快照**。ledger 只追加。同事务 outbox `benefit.fulfillment-event.v1`：AsyncAPI 现仅 `FULFILLMENT_EXPECTED|INTERNAL|PROVIDER`，本刀 **bump schema** 增加 `FREEZE|REDEEM|REFUND`（或等价 fact 字段），不要 silently 复用 INTERNAL。

**钉死同步 CAS**：命令事务内落账，202 body.`status` 已是目标态。禁止本刀再做成异步「202 ≠ USED」。多副本靠 `version` CAS；冲突 → `WALLET_VERSION_CONFLICT`。

### 3. 与 Remediation

`:refund` ≠ `REVERSE`。REVERSE 冲 AwardOrder item；refund 退已核销的用户资产。禁止互相调用。

### 4. 测试

- UNUSED→USED 一次；第二笔不同幂等键 → `WALLET_ALREADY_USED`。
- 同键重放 202 且 ledger 不双记。
- 并发双 redeem：只一笔 USED。
- redeem 金额 = 入账 faceValue，改模板面额不影响。
- PAUSED 模板不影响已发券核销。
- Slice1 冲正 / UNKNOWN 回归。

通知 Cursor：路径、202 字段、错误码、状态机。

---

## Codex 工作方式

1. 先 OpenAPI + 失败用例，再实现。
2. 不改 console。
3. 4b PR 与 4c PR 分开。
4. 每 PR：幂等 + 并发 + 模块边界。
5. 做完把字段表贴回 Cursor。
