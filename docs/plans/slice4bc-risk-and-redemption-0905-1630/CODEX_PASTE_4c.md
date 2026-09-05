# Codex 粘贴稿 — Slice 4c 券冻 / 核 / 退（只这一刀）

把本文件全文交给 Codex。**不要做 4a / 4b**，不要改任何 console。

对照：`docs/plans/slice4bc-risk-and-redemption-0905-1630/`  
父总则：`docs/plans/benefit-marketing-alignment-0905-0949/BACKEND_HANDOFF.md` §0。

失败 RFC 9457（`code` + `message`/`detail`）。金额 `long`，禁止 `double`。写操作必须 `Idempotency-Key`。

---

## 仓库

| 仓库 | 本刀 |
|---|---|
| `/Users/liruijun/personal/LLM/benefit-center` | OpenAPI + 迁移 + 同步 CAS 落账 + 事实事件 + 测试 |
| marketing-lowcode-platform / risk / workflow / recon / drools | **不改** |
| `benefit-console/` 以及任何 `frontend/` `*-console/` | **不改** |

`mvn verify` 要绿。做完把文末字段表贴回 Cursor。

---

## 禁止

- 改任何 console。
- 按**当前** SKU 模板重算面额 / 有效期。只读 `WalletEntry` 上的 `skuVersion` / `faceValueMinor` / `currency`。
- 用 Remediation `REVERSE` 冒充 `:refund`，或让 `:refund` 调用 `JdbcWalletRepository.reverseByItem`。
- 做 unfreeze（`FROZEN → UNUSED`）。
- 202 只表示「已受理、账还没落」（本刀禁止异步）。
- 错误码用 `INTERNAL_ERROR` 掩盖非法迁移。
- 给营销 / 对账加核销表单。
- 顺手做 4a Catalog、4b AwardIntent、C 端券包、现金渠道、开放平台。

---

## 现网锚点（不要猜）

- 券包只读：`GET /admin/v1/wallets/{subjectRef}`、`GET /admin/v1/wallets/{subjectRef}/entries`
- `WalletEntry` / `WalletEntryView` / `bc_wallet_entry` **没有** `version`
- 状态枚举已有：`UNUSED | FROZEN | USED | EXPIRED | REVERSED`
- `JdbcWalletRepository.createIfAbsent` 发券；`reverseByItem` 是履约冲正（`REVERSAL` ledger），**不是** 4c
- `bc_wallet_balance_ledger.entry_type` 现仅 `ISSUE` / `REVERSAL`；唯一键 `(tenant_id, operation_no, entry_type)`
- `bc_command_idempotency` 已存在，可复用
- AsyncAPI `FulfillmentEvent.entryType` 现仅 `ISSUE | REVERSAL`；`eventType` 现仅 `FULFILLMENT_EXPECTED | FULFILLMENT_INTERNAL | FULFILLMENT_PROVIDER`
- 控制台读 `/admin/v1`，写 award 已走 `/openapi/v1`。命令走 OpenAPI，**列表必须带回 `version`**，否则 Cursor 无法传 `expectedVersion`
- 错误枚举：`BenefitErrorCode` + OpenAPI Problem `code` + `BenefitExceptionAdvice`。4a 用 `SKU_*` + 409；4c 用 `WALLET_*`，不要掉进 `INTERNAL_ERROR`
- 权限：现网运营台是 `benefit.admin`。本刀命令也用 `benefit.admin`，**不要**先拆 `benefit.wallet.write` 把 console 挡在门外

---

## OpenAPI（先合契约）

```
POST /openapi/v1/wallet-entries/{entryId}:freeze
POST /openapi/v1/wallet-entries/{entryId}:redeem
POST /openapi/v1/wallet-entries/{entryId}:refund
  security: benefit.admin
  header: Idempotency-Key (required)
  body WalletEntryCommand:
    reason?: string
    merchantRef?: string
    expectedVersion?: int64
  202 WalletEntryCommandAcceptance { entryId, status, version }

WalletEntryView 增加：
  version: int64   # required；旧行迁移后为 0
```

**没有** `expectedStatus`。`Idempotency-Key` **不要**强制等于 `entryId`（和 AwardIntent 的 sourceRequestId 不同）。同键同体 → 202 重放首次结果；同键异体 → 409 `IDEMPOTENCY_PAYLOAD_CONFLICT`。

HTTP：

| 码 | 情况 |
|---|---|
| 202 | 同步落账成功或幂等重放；`body.status` **已是目标态** |
| 404 | `WALLET_ENTRY_NOT_FOUND` |
| 409 | `WALLET_ILLEGAL_TRANSITION` / `WALLET_ALREADY_USED` / `WALLET_VERSION_CONFLICT` / `WALLET_EXPIRED` / `IDEMPOTENCY_PAYLOAD_CONFLICT` |

把 `WALLET_*` 加进 `BenefitErrorCode`、OpenAPI Problem enum、`BenefitExceptionAdvice`（409；NOT_FOUND=404）。禁止这些路径落到 `INTERNAL_ERROR`。

---

## 存储

Flyway（mysql + h2）：

```
bc_wallet_entry.version BIGINT NOT NULL DEFAULT 0
```

领域 `WalletEntry`、查询 SQL、`createIfAbsent` INSERT 都带上。发券旧路径默认 `version=0`。

CAS：

```
UPDATE ... SET status=?, version=version+1, updated_at=?
WHERE tenant_id=? AND entry_id=? AND status=? AND version=?
```

`expectedVersion` 有值且不等于当前 → `WALLET_VERSION_CONFLICT`。未传则用读到的当前 version 再 CAS。并发双 redeem：一笔 USED，另一笔 409。

现金 ledger（仅 `CASH_BALANCE`）：

| 动作 | ledger `entry_type` | delta |
|---|---|---|
| freeze | 不写金额流水（只改状态） | — |
| redeem | `REDEEM` | `-faceValueMinor` |
| refund | `REFUND` | `+faceValueMinor` |

`operation_no` 用本次 `Idempotency-Key`（或由其派生且可重放）。券 / 码 / 实物只改状态，不写现金 ledger。

---

## 状态机

```
UNUSED → FROZEN     :freeze
UNUSED → USED       :redeem
FROZEN → USED       :redeem
USED   → REVERSED   :refund
```

禁止：

- `FROZEN → UNUSED`（无 unfreeze）
- `EXPIRED` / `REVERSED` 上任何写
- `USED` 上 `:freeze` / 再次 `:redeem` → `WALLET_ALREADY_USED`
- `expires_at < now` 仍 `UNUSED`/`FROZEN` 时核销 → `WALLET_EXPIRED`（可同时把行标成 EXPIRED，或只拒命令；选一种并钉测试）

金额 / `skuVersion` **只读 entry 快照**。改当前模板面额、把模板 PAUSED，已发券仍按入账值核销。

---

## 事实事件

同事务写 `bc_outbox_event` → `benefit.fulfillment-event.v1`。

AsyncAPI **必须 bump**：

- `eventType` 增加 `FULFILLMENT_WALLET`（不要 silently 复用 `FULFILLMENT_INTERNAL`）
- `entryType` 增加 `FREEZE | REDEEM | REFUND`

payload 带 `walletEntryId`、`skuId`、`skuVersion`、入账 `amountMinor`/`currency`（有则带）、目标 `status`。

---

## 与 Remediation 的边界

| | Remediation `REVERSE` | `:refund` |
|---|---|---|
| 对象 | AwardOrder item / 履约 | 已核销的用户资产 |
| 实现 | 现有 `reverseByItem` | 新命令服务 |
| ledger | `REVERSAL` | `REFUND` |
| 互相调用 | **禁止** | **禁止** |

两者都可以把条目打成 `REVERSED`，靠 ledger / 事实 `entryType` 区分。

---

## 测试

- UNUSED→USED 一次；第二笔不同幂等键 → `WALLET_ALREADY_USED`，ledger 不双记。
- 同 `Idempotency-Key` 重放 202，`version` / ledger 不前进。
- 并发双 redeem：只有一笔 USED。
- redeem 金额 = 入账 `faceValueMinor`；改当前模板面额不影响。
- PAUSED 模板不影响已发券核销。
- `:refund` 不走 `reverseByItem`；`REVERSE` 回归（Slice 1）仍绿。
- 过期券核销 → `WALLET_EXPIRED`。
- `expectedVersion` 错 → `WALLET_VERSION_CONFLICT`。
- 非 CASH 无现金 ledger 行。
- OpenAPI / 契约测试同步。

---

## 做完贴回 Cursor（照填）

```
POST /openapi/v1/wallet-entries/{entryId}:freeze|redeem|refund
  Idempotency-Key required（不强制等于 entryId）
  body: { reason?, merchantRef?, expectedVersion? }
  202 { entryId, status, version }   # status 已是目标态

GET /admin/v1/wallets/{subjectRef}/entries
  WalletEntryView.version: int64     # 刷新后给下一刀 expectedVersion

状态机：
  UNUSED → FROZEN → USED
  UNUSED → USED
  USED → REVERSED
  无 unfreeze

错误码：
  WALLET_ENTRY_NOT_FOUND     404
  WALLET_ILLEGAL_TRANSITION  409
  WALLET_ALREADY_USED        409
  WALLET_VERSION_CONFLICT    409
  WALLET_EXPIRED             409

权限：benefit.admin
```
