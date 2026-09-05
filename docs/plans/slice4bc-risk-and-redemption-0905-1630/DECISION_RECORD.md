# 决策记录：Slice 4b 发前风控 / Slice 4c 券核销

日期：2026-09-05  
范围：AwardIntent 出站前 risk check；用户资产 freeze / redeem / refund。  
不做：超预算 BPMN、补发冲正审批、现金渠道、开放平台。  
分工：Cursor 只做控制台；Claude / Codex 做后端。

对照：`docs/plans/benefit-marketing-alignment-0905-0949/`、`docs/plans/slice4a-sku-golive-approval-0905-1530/`。

## 1. 问题

4a 打通了 SKU 上线四眼。仍缺：

- 发奖指令进 outbox 前没有 risk-platform 拦截；拒绝了也会出 intent。
- 券包只有只读查询；交易侧不能按原分摊冻/核/退。

## 2. 备选方案

### 4b 风控

| 方案 | 做法 | 裁决 |
|---|---|---|
| A 营销读模型展示拒绝 | Assembler 后、outbox 前调 `POST /api/v1/risk/evaluations`；拒绝写入可查记录；发放 tab 只读展示原因 | **采用** |
| B 营销内嵌 risk 案件台 | 复制 CasesPage | **否决**（联邦控制台） |
| C 只 deep-link、营销不展示原因 | 运营必须切 risk-console | 可作 A 的补充外链，不能替代 A |

### 4c 核销

| 方案 | 做法 | 裁决 |
|---|---|---|
| A 权威在 benefit | `POST /openapi/v1/wallet-entries/{id}:freeze\|redeem\|refund`；console `/wallets` 有权限才出按钮 | **采用** |
| B 营销/对账也做核销表单 | 双写状态机 | **否决** |
| C 复用 Remediation REVERSE 当核销 | 边界混乱 | **否决**。REVERSE 是履约冲正；redeem/refund 是用户资产账本 |

## 3. 推荐架构

```text
4b
  POST /internal/v1/award-intents
    → AwardIntentAssembler（服务器重算，禁止前端金额）
    → 先查 mk_award_intent_outbox 与 mk_award_intent_block（同 sourceRequestId）
    → POST risk-platform /api/v1/risk/evaluations
         字段必须过现网 RiskRequest 校验（见假设 2），禁止自造 AWARD/MARKETING 枚举
    → ALLOW     → 写 mk_award_intent_outbox（CENTER/SHADOW/LEGACY 都过 check）
    → REJECT / 业务 CHALLENGE / REVIEW → 不写 outbox；写 block
    → 超时/5xx / 降级 CHALLENGE+DEGRADED_FEATURE_UNAVAILABLE → fail-closed，写 UNAVAILABLE block
    → 列表 GET 合并 outbox ∪ block，运营搜得到

  运营 marketing /operations?tab=awards
    → 只读；拒绝行展示 reason + 可选 risk-console ?q={sourceRequestId}

4c
  交易 / 客服
    → POST benefit /openapi/v1/wallet-entries/{entryId}:freeze|redeem|refund
    → 按 WalletEntry 上固化的 skuVersion / faceValueMinor，不读当前模板
    → 同事务改状态 + ledger + fulfillment fact
  benefit-console /wallets
    → UNUSED：冻 / 核
    → FROZEN：核（本刀无 unfreeze）
    → USED：退 → REVERSED（不回 UNUSED）
  营销：只消费事实事件，4c 前端零改动（无 subjectRef 不造 deep-link）
```

权威：

| 对象 | 权威 |
|---|---|
| 发前拦截规则 / 案件 | risk-platform / risk-console |
| 是否写出 AwardIntent | marketing Assembler + risk action |
| 用户资产账本 | benefit WalletEntry |
| 履约冲正 | 仍走 Remediation REVERSE，不是 :refund |

## 4. 视觉与端形态

- 不统一三套 UI。营销 teal `#087f75`；benefit Ant `#0F6F6A`；risk 保持本台。
- 4b 复用发放卡片 + `StateBanner`，不新色板。
- 4c 复用 `WalletDetailDrawer` + 44px 主按钮 + 确认 Modal。
- 桌面运营台。窄屏：营销 720 单列卡片；benefit 768 卡片 / 全宽 Drawer。不新断点。

## 5. 明确不改

- AwardIntent 提交表单。
- SkuPicker 拉非 ACTIVE。
- 营销 GovernancePage / risk CasesPage 互嵌。
- 4a Catalog 审批流。
- 在 recon 差异页做 redeem。
- 现金渠道、开放平台、C 端「我的券包」App。

## 6. 待用户确认的假设

1. 4b 先于 4c；一次只交给 Codex 一刀。
2. **钉死现网 `RiskRequest` 映射**（`channel` 仅 `MOBILE|WEB|ATM|API|BRANCH`；`bizType` 仅 `TRANSFER|REMITTANCE|PAYMENT|WITHDRAWAL`；`amount` `@Positive` ≥1）。4b **不改** risk 校验枚举。
   - `sourceId=MARKETING_AWARD`（自由字符串，需在 risk 登记规则绑定）
   - `txnId=sourceRequestId`
   - `accountNo=subjectRef`（内部；读模型仍只暴露 `subjectHash`）
   - `channel=API`，`bizType=PAYMENT`
   - CASH：`amount`=组装后最小单位（≥1），`currency`=币种
   - 非 CASH：`amount=1`，`currency=XXX`（禁止 0，否则 400）
3. 业务 `CHALLENGE`/`REVIEW` 本刀等同不发（`riskAction` 保留原值）。引擎降级 `CHALLENGE` + `DEGRADED_FEATURE_UNAVAILABLE` 记 `UNAVAILABLE`，不是业务挑战。
4. risk 宕机 / 超时 / 5xx fail-closed：不写 outbox；内部触发返回 **503** `RISK_UNAVAILABLE`（可重试）。业务拦截返回 **202** + `RISK_BLOCKED` 视图（与 Slice 2 受理语义一致，避免 Drools 把 409 当失败狂重试）。
5. 拒绝可查：独立表 `mk_award_intent_block`（不改 `ck_award_intent_status`）。`GET /award-intents` UNION + 统一 seek。禁止只打日志。
6. 4c 解冻（unfreeze）本刀不做。退券终态 **REVERSED**，不回 UNUSED。
7. 营销 4c 零前端。
8. 4c console 权限仍 `benefit.admin`，不先拆 `benefit.wallet.write`。
9. 4c 同步 CAS 落账：202 body 已是目标态；`bc_wallet_entry` 需新加 `version`。请求体 `{ reason?, merchantRef?, expectedVersion? }`，**没有** `expectedStatus`。
