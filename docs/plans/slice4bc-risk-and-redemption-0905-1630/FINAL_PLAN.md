# 执行计划：Slice 4b 发前风控 / Slice 4c 券核销

日期：2026-09-05  
决策见 `DECISION_RECORD.md`。后端合同见 `BACKEND_HANDOFF.md`。

| 角色 | 仓库 | 做什么 |
|---|---|---|
| Codex / Claude | marketing 后端、risk 绑定、benefit 后端 | 先 4b 后 4c；不改 console |
| Cursor | marketing console、benefit-console | 接口未到不写假数据 |

---

## Goals

1. 4b：risk `REJECT` / `CHALLENGE` / `REVIEW` / 不可用 → **不写** AwardIntent outbox；运营能在发放 tab 看到拦截原因。
2. 4c：benefit 能按原分摊冻/核/退；`/wallets` 有权限才出按钮。
3. 规则与案件仍在 risk-console；核销状态机只在 benefit。
4. 任何控制台仍没有「提交发奖」表单。

## Non-goals

- 超预算 / 补发冲正 BPMN。
- 营销或 recon 内核销表单。
- 解冻、C 端券包、现金渠道、开放平台。
- 合并 SPA；硬编码 SKU / 决策 / 券包。
- 改 4a Catalog 审批。

后置做法（2b、等价 SKU、C 端券包、解冻、权限拆、现金 / 开放平台 / BPMN 等）见 `docs/plans/benefit-marketing-alignment-0905-0949/DEFERRED.md`。  
联邦编排见 `docs/plans/benefit-marketing-alignment-0905-0949/FLOW.md`。

---

## 视觉方向

沿用既有语言。

| 台 | Tokens |
|---|---|
| 营销 | `styles.css` teal `#087f75`，发放卡片 `.award-intent-*` |
| 权益 | Ant `#0F6F6A`，`WalletEntryStatusTag`，Drawer 480 / 100% |
| 风控 | 不改；只外链 `/decisions?q=` |

---

## 路由与页面流

### 4b

```text
/operations?tab=awards&campaignId=
  → 列表含 RISK_BLOCKED
  → StateBanner 展示 riskReason（不是「已发放」）
  → runtime.riskConsoleOrigin 非空才外链 /decisions?q={sourceRequestId}
禁止：提交发奖
```

无新路由。

### 4c

```text
/wallets → 输入 subjectRef → 条目
  UNUSED：冻 / 核（确认 Modal + 原因）
  FROZEN：核
  USED：退
  成功：message.info「已受理」+ 用 202 body.status 刷新（同步落账，body 已是 USED/FROZEN/REVERSED）
```

无新路由。`?subject=` 已有则沿用。

---

## 组件树

### 4b 营销（复用 vs 新建）

```
AwardIntentsPanel                 复用，扩 status / 原因
  StateBanner                     复用
  riskDecisionHref()              新建，仿 benefitOrderHref
schemas.ts / client.ts            扩 AwardIntentView
runtime.ts                        可选 riskConsoleOrigin
```

### 4c 权益

```
WalletsPage                       复用
WalletDetailDrawer                扩动作区
  confirm Modal                   antd Modal
benefit.ts                        freeze/redeem/refund
```

---

## 状态与边界

### 发放 tab

| 态 | 行为 |
|---|---|
| 无 campaignId | 不请求 |
| demoMode | 不请求 |
| RISK_BLOCKED | warn banner + 原因；无订单外链 |
| DEAD | 现有 lastError |
| origin 空 | 不渲染 risk `<a>` |
| 窄屏 | 现有单列卡片 |

### 券包 Drawer

| 态 | 行为 |
|---|---|
| 未查 / 404 | 现有空态 |
| 无写权限 | 无动作按钮 |
| 进行中 | 按钮 pending；Idempotency-Key |
| 202 | info「已受理」，立刻用返回 status 更新；再 GET 校对 |
| 409 已核 | 映射稳定码，不假装成功 |
| 窄屏 | 全宽 Drawer，按钮 44px |

---

## API（前端只消费）

### 4b marketing

```
GET /api/v1/award-intents?campaignId=
  合并 mk_award_intent_outbox ∪ mk_award_intent_block
  AwardIntentView 增：
    status += RISK_BLOCKED
    riskAction?: CHALLENGE|REVIEW|REJECT|UNAVAILABLE   # 仅 RISK_BLOCKED 行
    riskReason?: string
    riskDecisionId?: string
  RISK_BLOCKED 无 benefitOrderNo；deliveryResult 保持空

POST /internal/v1/award-intents   # console 不调
  业务拦截 → 202 + RISK_BLOCKED 视图
  risk 不可用 → 503 RISK_UNAVAILABLE
```

运行时可选 `RISK_CONSOLE_ORIGIN` / `VITE_RISK_CONSOLE_ORIGIN`，默认空。
Helm `console.yaml` + `deploy/docker/config.js.template` 与 `benefitConsoleOrigin` 同级。

### 4c benefit

```
POST /openapi/v1/wallet-entries/{entryId}:freeze
POST /openapi/v1/wallet-entries/{entryId}:redeem
POST /openapi/v1/wallet-entries/{entryId}:refund
  Idempotency-Key required
  body: { reason?, merchantRef?, expectedVersion?: int64 }
  202 { entryId, status, version }     # 同步落账，status 已是目标态
```

错误码：`WALLET_ENTRY_NOT_FOUND`、`WALLET_ILLEGAL_TRANSITION`、`WALLET_ALREADY_USED`、`WALLET_VERSION_CONFLICT`。
`bc_wallet_entry` 本刀加 `version`（现状无此列）。退券终态 `REVERSED`。

---

## 响应式

不新断点。营销 720；benefit 768/992。4c 窄屏能看条目并点确认。  
Drawer 动作按钮走 **footer** 类（现有 `.page-header-actions .ant-btn` 的 44px **罩不到** Drawer），例如 `.wallet-entry-actions .ant-btn { min-height: 44px }`。  
验收：Pixel 7 发放 tab 能读拦截原因；Pixel 5 `/wallets` Drawer footer 动作 ≥44px。

---

## 文件清单（Cursor）

### 4b `frontend/apps/console`

- `schemas.ts`、`client.ts`、`runtime.ts`、`config.js`、`vite-env.d.ts`
- `deploy/docker/config.js.template`、`deploy/helm/marketing-platform/templates/console.yaml`
- `AwardIntentsPanel.tsx` + test
- e2e：仍断言无提交发奖

### 4c `benefit-console`

- `types.ts`、`benefit.ts`
- `WalletDetailDrawer.tsx`、`WalletsPage.test.tsx`
- e2e Pixel 5：mock 后 UNUSED 可见核销按钮

禁止改 Java / OpenAPI。Codex 禁止改 console。

---

## 步骤

```text
T0  批准本计划
T1  Codex：4b（risk check + 拒绝读模型 + 测试）
T2  Cursor：发放 tab 展示 RISK_BLOCKED（OpenAPI 合入后）
T3  Codex：4c wallet 三动作 + 事实事件 + 测试
T4  Cursor：/wallets 动作
T5  冒烟：拒发看不见订单；核销 202 后刷新为 USED
```

T2/T4 门禁：本地能打到新字段 / 202 或稳定 409。

### T5 收口（2026-09-05）

4c 已联机：

- 重建 `deploy-console`（`:8083`）与 `deploy-benefit-center`（`:8183`）。旧镜像没有 `:redeem`，列表也没有 `version`。
- `POST :redeem` → 202 `{ status: USED, version: 1 }`；同键重放 202；另键 409 `WALLET_ALREADY_USED`。
- 运营台 `/wallets?subject=smoke-user-4c`：UNUSED 冻/核，核销后抽屉变为「已使用」，USED 只出「退券」。核销按钮高度 ≥44px。

4b 已联机（DEV 头，未走 `--secure`）：

- 营销 API `MARKETING_SECURITY_MODE=DEV` / `MARKETING_DEV_HEADERS_ENABLED=true`；控制台 `AUTH_MODE=DEV`、`DEMO_MODE=false`。
- 风控：`fraud-gateway` `:8082`（dev + H2）+ `risk-redis` `:16379`；`HSET feature:{risk-blocked-user-4b} blacklist true`。
- 重建了 9 月 4 日旧镜像：`benefit-funding-service`、`edge-gateway`。`RISK_PLATFORM_BASE_URL=http://host.docker.internal:8082`。
- `POST /internal/v1/award-intents` `sourceRequestId=award-4b-reject-1788614857` → 202 `{ status: RISK_BLOCKED, riskAction: REJECT, riskReason: BLACKLIST_ACCOUNT, benefitOrderNo: null, attempts: 0 }`。
- `GET /api/v1/award-intents?campaignId=mock-cmp-new-user` 仅该拦截行；权益 `GET /openapi/v1/award-orders?sourceSystem=drools-activity&sourceRequestId=...` 在 `dev-tenant` / `retail-cn` 均为 404。
- 发放 tab `http://localhost:8084/operations?tab=awards&campaignId=mock-cmp-new-user`：红标「风控拦截」，横幅「风控拒绝，未写出发放指令」+ `BLACKLIST_ACCOUNT`，无「打开权益订单」，有「打开风控决策」→ `http://localhost:15173/decisions?q=award-4b-reject-1788614857`。

订单 → 券包：`GET AwardOrder.recipientRef` 已由 Codex 补上。Drawer 用它跳 `/wallets?subject=&entry=`。

---

## 测试

- 营销 Vitest：RISK_BLOCKED 展示原因；demo 不请求；无提交按钮。
- Playwright Pixel 7：发放 tab 可读拦截文案。
- 权益 Vitest：UNUSED 显示核销；USED 显示退；404 空态不回归。
- Playwright Pixel 5：Drawer 主按钮 ≥44px。
- 后端证据见 BACKEND_HANDOFF。

---

## 验收

- [x] AC-01 risk REJECT 后无 outbox 行，发放 tab 为 RISK_BLOCKED。（真栈 `award-4b-reject-1788614857` / `BLACKLIST_ACCOUNT`）
- [ ] AC-02 risk 不可用 fail-closed，不写出 intent。（后端单测有；真栈未打）
- [x] AC-03 无「新建 AwardIntent」表单。
- [x] AC-04 UNUSED 能冻/核；重复核销不双花。
- [ ] AC-05 redeem/refund 用原 faceValue / skuVersion；refund 后为 REVERSED。（redeem 已联机；refund 未联机）
- [x] AC-06 营销 4c 无核销表单。
- [x] AC-07 前端无假决策 / 假券包。
- [x] AC-08 Pixel 7 发放 tab 拦截原因可读。
- [x] AC-09 Pixel 5 `/wallets` 动作按钮 ≥44px。

---

## 风险与回滚

| 风险 | 缓解 | 回滚 |
|---|---|---|
| risk 超时被当成放行 | 默认 fail-closed | 关 check，回 Slice 2 |
| 拒绝不可查 | 必须落 RISK_BLOCKED 读模型 | — |
| 双核销 | CAS + 幂等键 | 停 openapi 写 |
| 202 当已核销 | 文案 + 刷新 | 改 copy |
| 与 Remediation REVERSE 混淆 | 文档+UI 写「退券」不是「履约冲正」 | — |

---

## 一页纸

**Codex** 打开 `BACKEND_HANDOFF.md`，先 4b 再 4c。不改 console。

**Cursor** 等 OpenAPI 后再改 `AwardIntentsPanel` / `WalletDetailDrawer`。
