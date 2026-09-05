# 后置与本阶段不做（后续再开刀）

日期：2026-09-05  
对照：同目录 `DECISION_RECORD.md`、`FINAL_PLAN.md`、`BACKEND_HANDOFF.md`、`FLOW.md`；  
`docs/plans/slice4a-sku-golive-approval-0905-1530/`、`docs/plans/slice4bc-risk-and-redemption-0905-1630/`。

本文只记**怎么做、谁做、先做什么**。批准某一刀之前，不改代码。

分工不变：Cursor 只改各控制台前端；Claude Code / Codex 做 Java / OpenAPI / 迁移 / BPMN。禁止前端硬编码业务数据。任何控制台都不做「提交发奖」表单。

---

## 总原则（后置刀也适用）

- 联邦三台：营销 / 权益 / 流程，只对契约和 deep link，不合并 SPA。
- 金额、SKU、核销状态只在服务端算。
- 一次只开一刀；OpenAPI 合入并能 200 / 空数组再写页面。

建议顺序（由小到大）：

```text
等价 SKU 表单项
  → Slice 2b Journey award
  → C 端「我的券包」
  →（按需）权限拆分 / 解冻
  → 超预算与补发 BPMN
现金 / 开放平台 / 积分 / E 卡：另立产品线，不插入上表
```

---

## 计划里明确后置（不是 4a / 4b / 4c）

### 1. Slice 2b：Journey award 节点

画布只配 `benefitId` / 场景，不含金额。

**现状**

- 画布已有琥珀色「发放权益」，类型是 `journey.grant`，`config` 为空。
- 运行时投影 `GRANT` → `mk.benefit.command.v1`（旧命令），**未进入** `AwardIntentAssembler` + 4b 风控门禁。
- 相关：`frontend/apps/console/src/features/designers/JourneyDesignerPage.tsx`、`graph.ts`；`journey-service` `JourneyOutputProjector`。

**怎么做（一刀）**

| 层 | 做什么 | 不做什么 |
|---|---|---|
| Codex 契约 | 节点 `stableTypeId=journey.award`（或把 `grant` 升级为 award，不要并存两套发奖） | 节点里加 `amount` / `faceValue` / `skuId` |
| Codex 运行 | 到达节点 → 用 enrollment 的 `campaignId + definitionVersion + subject + sourceRequestId` 调已有 `POST /internal/v1/award-intents` | 再写一条绕过风控的 `mk.benefit.command.v1` |
| Cursor 画布 | Inspector 只配：`benefitId`（下拉已有 Benefit，且已绑 ACTIVE SKU）、场景枚举（`CLAIM` / `POST_PAY` / `TARGETED`） | 面额输入、SKU 重选、发奖预览金额 |

场景只描述**何时触发**。金额仍由 OfferToken / BenefitDefinition + 模板重算。发布门禁沿用 4a：未绑 ACTIVE SKU 的 `benefitId` 不能进生产制品。

运营验收：同一 `sourceRequestId` 出现在 `/operations?tab=awards`；拒发仍是 `RISK_BLOCKED`，权益侧无单。

**依赖**：必须吃现成 4b 门禁，不要为 Journey 再开「免风控 GRANT」。

---

### 2. Catalog `equivalentSkuId`

后端可接，前端 Slice 1 故意没做。

**现状**

- OpenAPI / `SkuView` / 营销 `benefitSkuSchema` 已有字段。
- 后端校验：不能指向自己、目标须存在（`JdbcCatalogAdminService`）。
- Catalog 抽屉未画该选择器。

**怎么做（很小一刀，几乎纯前端）**

- Cursor 在 `benefit-console` Catalog 抽屉加「等价 SKU」：只列同租户、ACTIVE、非自己的模板。
- 保存走现有 `PUT /admin/v1/skus/{skuId}`，带上 `equivalentSkuId`；清空则 `null`。
- 列表可加一列「等价于 xxx」，空则不展示。

**先不要做的运行时语义**（需另开 Codex 刀，否则选择器只是死字段）：

- 缺货是否自动切等价、要不要二次审批、库存从谁扣、`AwardItem` 记原 SKU 还是等价 SKU。

产品默认：选择器先上；自动切货等「等价对已审批」的履约策略单独立项。

---

### 3. C 端「我的券包」

`GET /openapi/v1/me/wallet`。本阶段只做了客服 `/wallets`。

**现状**

- 权威在权益账本。客服：`GET /admin/v1/wallets/{subjectRef}` + 冻 / 核 / 退。
- `/openapi/v1/me/wallet` 写在对齐合同里，OpenAPI **尚未落地**。

**怎么做（独立产品刀，不是运营台加 Tab）**

1. Codex（benefit-center）
   - `GET /openapi/v1/me/wallet` 与 entries：`subjectRef` **只从已认证 token 取**，禁止 query / header 冒充别人。
   - 权限用终端用户 scope（例如 `benefit.wallet.me`），**不要**把 `benefit.admin` 发给 C 端。
   - 只读；C 端不暴露客服冻 / 核 / 退（交易核销走支付回调调 `:freeze|:redeem`）。
2. Cursor
   - **新 C 端壳**（H5 / 小程序 WebView），不要塞进营销低代码或权益运营台。
   - 复用券状态文案（未用 / 已冻 / 已用 / 已退），数据全部接口来。
   - 窄屏是一等公民（卡片、主按钮 ≥44px），和运营台「桌面为主」相反。

客服 `/wallets` 继续按 subject 点查，不预拉全站用户。两边读同一账本。

---

### 4. 解冻：`FROZEN → UNUSED`

本阶段不做。

**现状**

- 4c 合同与 `WalletCommandContractTest` 写明：**没有** `:unfreeze`。
- 客服：UNUSED → 冻 / 核；FROZEN → 核；USED → 退成 `REVERSED`（不回 UNUSED）。

**以后若要做（单独刀，偏风控）**

- Codex：`POST /openapi/v1/wallet-entries/{id}:unfreeze`，`Idempotency-Key`，仅 `FROZEN` 且未核销；同事务出事实事件。超时 / UNKNOWN 只查原 `operationNo`。
- 权限至少高于普通核销（届时再引入 `benefit.wallet.write`，或继续 `benefit.admin` + 审计）。
- Cursor：只在 FROZEN 出「解冻」，确认 Modal，成功回到未使用；**没有**这条 API 就不画按钮。

默认继续不做：冻结是核销前占位，解冻等于把已占库存 / 资格吐回去，比核销更容易误用。

---

### 5. 权限拆分：继续用 `benefit.admin`

不先拆 `benefit.wallet.write`。

**现状**

- Catalog、券包动作、4a 提交审批都挂 `benefit.admin`。
- Slice 1 写明：先拆 `benefit.wallet.read/write`，客服进不了台。

**怎么做（等出现第二种角色再拆）**

1. 只读客服：`benefit.wallet.read` → `/wallets` 能查、无冻核退；Catalog 仍要 `benefit.admin`。
2. 账本写：`benefit.wallet.write` → 冻 / 核 / 退（和解冻若存在）。`benefit.admin` 继续隐含全开，避免旧 token 全 403。

Cursor：`can('benefit.admin') || can('benefit.wallet.write')` 控按钮；无写权限只读 Drawer。  
Codex：OpenAPI `security` + `BenefitSecurityConfig` 与 console 同步改。  
营销发放 tab **继续** `trace:read`，不要换成 benefit scope。

---

## 本阶段明确不做

合并三个控制台、营销上做核销 / 发奖表单、现金渠道、开放平台、积分 / E 卡、超预算 BPMN、补发冲正审批。

以后若重开，也按下面做，不要另起一套。

### 合并三个控制台

继续否决。运营切台用 origin + query（订单、券包、风控决策、流程待办）。不抽 `@platform/console-ui`：三套色板和栈不合（营销自研 teal / 权益 Ant `#0F6F6A` / 对账 Ant `#315EFB`）。

### 营销上做核销 / 发奖表单

继续否决。发奖只走服务端 Assembler；核销只在权益 `/wallets` 或交易回调。营销发放 tab 只读 + 外链。谁在 Offer / Journey 里加「金额 + 提交」都算回退。

### 现金渠道

新聚合 + `ChannelAdapter` Strategy，不进 `SkuTemplate`，不进营销画布。模板只描述资产形状；现金三方勾兑在 recon `BENEFIT_CASH_3WAY`。没有真实渠道适配器就不要在 Catalog 上画「打款」。

### 开放平台

独立 ISV 身份、独立 audience、独立限流和签名。不要复用运营台 `benefit.admin`，不要把 admin API 裸暴露。C 端 `/me/wallet` 可以是其只读子集，但 ISV 发奖必须走和营销一样的幂等 `AwardIntent`，禁止 ISV 自带金额。

### 积分 / E 卡

新 `benefitType` + 新账本分录类型，模板仍不含玩法。营销只绑新 SKU。不要把积分当「面额为 1 的券」硬塞现核销状态机。

### 超预算 BPMN、补发冲正审批

照抄 4a，不要新发明：

```text
业务写状态 + outbox workflow.command.start.v1
  → 禁止事务里 HTTP 调 workflow
  → 流程台 BPMN + 待办
  → inbox 回执 CAS 改业务状态
```

| 场景 | 消费方 | 发起 | 落地 |
|---|---|---|---|
| 已有 | benefit SKU 上线 | `POST :submit-for-approval` | `SKU_GO_LIVE_APPROVE/REJECT` |
| 后置 | 活动 / 预算超阈 | **营销** control 发 start（预算权威在营销） | 回执后再放行发布 / 批次 |
| 后置 | 补发 / 冲正 | **recon 建议 → benefit Remediation** 发 start | 回执后才执行 REISSUE/REVERSE |

超预算不要做成权益模板字段。补发冲正不要复用 `:refund`（那是用户账本；Remediation 才是履约纠错）。  
Cursor：对应台只加「已提交审批」只读 + 流程台外链，和 Catalog 4a 一样，不嵌工作流设计器。

---

## 开下一刀时怎么切

| 下一刀 | 谁先 | 前端落点 | 后端落点 |
|---|---|---|---|
| 等价 SKU 选择器 | 可先 Cursor（字段已在） | `benefit-console` `CatalogPage` 抽屉 | 已有；自动切货另议 |
| 2b Journey award | Codex 契约 + 运行，再 Cursor inspector | `JourneyDesignerPage` / `graph.ts` | Assembler 触发，废旧 GRANT 双发 |
| C 端券包 | Codex `/me/wallet`，再新 H5 | **新应用**，不进现有三台 | subject 仅来自 token |
| 解冻 / 权限拆 | 等有角色或客服强需求 | `/wallets` 按钮显隐 | 新 path 或新 scope |
| 超预算 / 补发审批 | Codex BPMN + inbox，照 4a | 只读状态 + 外链 | `command.start.v1` |

指定下一刀后再走 frontend-plan / Codex handoff，不要把上表一次做完。
