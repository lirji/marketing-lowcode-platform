# 执行计划：权益 / 营销 / 对账对齐

日期：2026-09-05  
分工：

| 角色 | 仓库 | 做什么 |
|---|---|---|
| **Claude / Codex** | benefit-center、marketing 后端、recon、drools 连接器 | 见同目录 `BACKEND_HANDOFF.md` |
| **Cursor** | 各控制台前端 | 本文前端章节；**接口未到不写页面假数据** |

决策见 `DECISION_RECORD.md`。差距来源：桌面文档《权益发放中台与京东淘天能力差距》。

---

## Goals

1. 履约核保持不动；产品层按限界上下文补齐，而不是做成第二个活动平台。
2. Slice 1：权益中台能配置模板、查询券包；营销能绑定已投放 SKU。
3. Slice 2：营销（或过渡期 drools）服务器端产出 `AwardIntent`；控制台能看发放状态，不能手工发奖。
4. Slice 3：对账能看到应发 / 已发差异并走已有补救页。
5. 页面设计态走营销低代码；运营点查留在 benefit / recon。
6. 后端按三高 + DDD + 多层缓存实现（后端合同，不由 Cursor 落地）。

## Non-goals

- 合并三个 SPA，或不抽跨仓 UI 包。
- 在 benefit-center 做人群、会场、互斥、画布。
- 在营销前端做渠道状态机、三方勾兑、手工 AwardIntent。
- Slice 1–2 做积分 / E 卡 / 膨胀红包 / 真实现金渠道 / 开放平台。
- 把营销 Offer/Journey 设计器做成手机可编辑工作台。
- 改能力门户 catalog（除日后加一句说明外，本阶段不做）。
- 以 drools-demo 为新的产品主路径。

---

## 视觉方向与设计参考

**沿用既有语言，不重做视觉。**

| 控制台 | 依据 | Tokens |
|---|---|---|
| 营销 | `frontend/apps/console/src/styles.css`、`components/ui.tsx` | 主色 `#087f75`，圆角 7/12，间距 workspace 34×36，Inter + PingFang |
| 权益 | `benefit-console/src/theme/theme.ts`、`colors.ts` | Ant 主色 `#0F6F6A`，radius 8/12，content max 1440 |
| 对账 | `recon-console/src/theme/colors.ts` | Ant 主色 `#315EFB`，布局与 benefit 同构 |

低代码页继续用营销 `LowCodeDesigner`（palette / canvas / inspector），不把 Catalog 改成 React Flow。运营页继续 Ant Table + Drawer。

参考站只用于核对 B 端信息层级（SaaS Interface 的「目录 + 抽屉编辑」），不引入新色板。

---

## 路由与页面流

### Slice 1 用户流

```text
运营从门户进权益中台 /catalog
  → 建模板（有效期 / 状态 / 限额）→ 保存
  → 库存 / 路由仍走现页
客服只进 /wallets（不是 /catalog）
  → 输入 subjectRef → 看券包条目

运营从门户进营销 /designers/benefit
  → 选已投放 SKU（接口列表）→ 保存 BenefitDefinition
  → 活动发布门禁由后端校验 SKU ACTIVE
```

### Slice 2 用户流

```text
营销 /operations?tab=awards&campaignId={id}
  → 页内活动下拉（复用 campaigns 列表）必选后才请求 intent
  → 只读 PENDING/SENT/DEAD；DEAD 用配置的权益台 origin 打开 /orders?q={sourceRequestId}
  → 权益订单 Drawer 若有 walletEntryId，链到 /wallets?subject=
禁止：任何控制台出现「提交发奖」表单
Slice 2 验收不含 Journey 画布。journey.award 节点另标 Slice 2b。
```

### 路由变更

**benefit-console**（`src/router.tsx`）

| 路径 | 动作 |
|---|---|
| `/catalog` | 扩展 SKU 抽屉字段，不改路径 |
| `/wallets` | **新建**，客服券包查询 |
| `/orders` | 订单 Drawer 增加「入账券包」只读链（Slice 1 后半） |

导航：`AppLayout` 增加「用户资产」。

**marketing console**

| 路径 | 动作 |
|---|---|
| `/designers/benefit` | 扩展：SKU 选择器 + 只读模板摘要 |
| `/assets` 权益 tab | 展示 `benefitSkuId` |
| `/operations` 新 tab「发放」 | Slice 2：intent 列表，只读。权限沿用 `trace:read`（与点查同一运营角色）。`campaignId` 来自 query，缺则 Empty「请先选活动」 |

不新增营销一级导航「券包」。

**recon-console**

| 路径 | 动作 |
|---|---|
| `/discrepancies`、权益补救 | Slice 3 接新字段；无新路由 |

---

## 组件树

### 营销（复用 vs 新建）

```
BenefitEditorPage                    复用
  PageHeader / DemoBanner / Panel    复用 ui.tsx
  SkuPickerField                     新建 features/designers/SkuPickerField.tsx
    调 GET /api/v1/benefit-skus
  TemplateSummary                    新建（只读：状态/有效期/面额/限额）
  绑 SKU 后：validity / 面额类输入只读，以模板为准
  仍可编：门槛、范围、出资、退款条款（玩法层，不进 SkuTemplate）
AssetsPage 权益列                    改一行
OperationsPage AwardIntentsPanel     Slice 2 新建
```

### 权益中台

```
CatalogPage                          复用
  SkuDrawer Form                     扩展字段，沿用 antd Form rules
WalletsPage                          新建 pages/WalletsPage.tsx
  PageHeader / AsyncState            复用
  搜索条 + Table / mobile-data-card  复用 Inventory 模式
  WalletDetailDrawer                 新建
OrderDetailDrawer                    增加 walletEntryId 链接
StatusTag                            扩展模板 status、券状态，不复用 recon 的 Tag
```

### 对账

不新建页面。`DiscrepancyDetailDrawer` / 权益补救表补「应发源」只读区。

---

## 状态与边界（逐页）

### 营销 BenefitEditor

| 态 | 行为 |
|---|---|
| loading | 按钮 pending；SKU 列表 skeleton 一行即可 |
| empty SKU | `EmptyState`：权益中台尚无 ACTIVE 模板，不造假列表 |
| error | `StateBanner` + `problemDetail` |
| success | 现有保存 banner |
| 无写权限 | 选择器 disabled |
| demoMode | **不发起** `benefit-skus` 请求；固定提示「演示模式不绑定真实 SKU」 |
| 校验 | `benefitSkuId` 可空草稿；提交发布由后端拒绝未投放 SKU |
| 已绑 SKU | `validity` / 面额只读展示 TemplateSummary；保存仍提交原 policy 玩法字段，不回写模板有效期 |
| hydrate | live 打开已有 benefit 时覆盖 `DEFAULT_FORM`，禁止把示例 ID 当真实数据展示 |

### 权益 Catalog

| 态 | 行为 |
|---|---|
| loading | 现有 `PageSkeleton` |
| empty | 现有 Empty |
| CASH | 保持面额 + 币种必填 |
| RELATIVE 有效期 | `relativeDays` 必填；ABSOLUTE 则起止必填 |
| 限额 | Slice 1 做 `userLimitPerDay` / `userLimitTotal` / `dailyQuota`；`equivalentSkuId` 后端可接、**前端 Slice 1 不做** |
| status vs enabled | 表单主控是 `status`。`enabled` 由后端派生：仅 `ACTIVE` 为 true。列表展示 StatusTag，去掉易混淆的独立 Switch |
| PENDING_APPROVAL | Slice 1 不做。运营可 DRAFT ↔ ACTIVE / PAUSED（需 `benefit.admin`）。审批态留给 Slice 4 |
| 窄屏 | 现有 Alert「建议桌面配置」+ 全宽 Drawer |

### 权益 Wallets

| 态 | 行为 |
|---|---|
| 未输入 subjectRef | placeholder Empty，不预拉全量用户。placeholder：`用户稳定引用，如 Casdoor sub 或营销 subject token`。前端只做非空 + 长度 ≤128，不做跨系统 crosswalk |
| 无记录 | Empty「该用户暂无权益」；HTTP 404/`WALLET_NOT_FOUND` 用同一空态，与 400 格式错误分开 |
| error / 403 | `ErrorState` |
| UNUSED / FROZEN / USED | `StatusTag` |
| 窄屏 | `mobile-data-card`，按钮 min-height 44px |

### 营销发放 tab（Slice 2）

| 态 | 行为 |
|---|---|
| 无 campaignId | Empty「请先选择活动」，**不请求** intent 列表 |
| DEAD | 展示原因码；`runtimeConfig.benefitConsoleOrigin` 非空才显示「打开权益订单」。无 origin 则只展示 `sourceRequestId` 可复制 |
| 空列表 | Empty「本活动尚无发放指令」 |
| 宽表 | 用 TraceSearch 式卡片，**禁止**复用 `ops-list`（min-width 690，窄屏会撞 AC-10） |

---

## API 契约（前端只消费）

后端未合并前，前端禁止用常量数组冒充。

### Slice 1 — benefit-center

```
GET  /admin/v1/skus
PUT  /admin/v1/skus/{skuId}
     body 增：status, validityType, validFrom, validTo, relativeDays,
             dailyQuota, userLimitPerDay, userLimitTotal, equivalentSkuId
GET  /admin/v1/wallets/{subjectRef}
GET  /admin/v1/wallets/{subjectRef}/entries?status&skuId&limit
```

权限：Slice 1 仍为 `benefit.admin`（与现网整站门禁一致）。不做 `benefit.wallet.read`。
`AwardItem` 增可选 `walletEntryId`（T1 必须出，T6 才能链券包）。

`SkuView` / `WalletEntry` 类型加到 `benefit-console/src/api/types.ts`，经 `api/benefit.ts`。

### Slice 1–2 — marketing

```
GET  /api/v1/benefit-skus?status=ACTIVE
PUT  /api/v1/benefits/{id}   + benefitSkuId
GET  /api/v1/benefits        BenefitView + benefitSkuId
GET  /api/v1/award-intents?campaignId=   Slice 2
```

Zod：`frontend/apps/console/src/shared/api/schemas.ts` 扩展 `benefitViewSchema`。

错误码前端要映射：`SKU_NOT_ACTIVE`、`WALLET_NOT_FOUND`、`UNKNOWN_MUST_QUERY`。

营销运行时增可选 `benefitConsoleOrigin`（`config.js` / `CONSOLE_BENEFIT_ORIGIN`，默认空）。权益台增可选 `VITE_MARKETING_CONSOLE_ORIGIN`。空则不渲染跨台 `<a>`。

`GET /api/v1/benefit-skus?status=ACTIVE` 只返回 `status=ACTIVE` 且后端 `enabled=true` 的模板。

### Slice 3 — recon

沿用 `/recon/*`。T8 只改 `DiscrepancyDetailDrawer` 只读区，字段（后端 T7 写入，缺则整块不渲染）：

- `expectedSourceSystem`
- `marketingSourceRequestId`
- `benefitOrderNo`

`BenefitRemediationsPage` Slice 3 不改。ODS 未开时上述字段为空，前端 **隐藏**「应发源」区块，不展示「—」。

---

## 响应式与移动端

**定性：桌面内部运营台。** 移动端写入 non-goals 的「可编辑设计器」，但验收仍要有一个窄屏查看项（技能要求）。

| 台 | 断点 | 小屏策略 |
|---|---|---|
| 营销 | 720 抽屉导航（`feature-styles.css`） | Benefit 表单单列；SKU 选择器不横向溢出；画布不保证可拖 |
| 权益 | 768 卡片 / 992 藏 Sider | Catalog / Wallets 走 `mobile-data-card` + 全宽 Drawer |
| 对账 | 同权益 | 不改 |

触控：benefit/recon 按钮 ≥44px；营销壳层菜单保持现有 34px，本阶段不整改。  
无 hover 依赖的新交互；Wallets 搜索用提交按钮，不只靠 hover。  
不新增 WebView / 微信适配。

---

## 文件级改动清单（Cursor）

### 营销 `frontend/apps/console`

Slice 1：

- `src/shared/api/schemas.ts` — `benefitSkuId`、`benefitSkuSchema`
- `src/shared/api/client.ts` — `benefitSkus()`
- `src/shared/config/runtime.ts`、`src/vite-env.d.ts`、`deploy/docker/config.js.template` — `benefitConsoleOrigin`
- `src/features/designers/BenefitEditorPage.tsx`
- `src/features/designers/SkuPickerField.tsx`（新）
- `src/features/assets/AssetsPage.tsx`
- `src/features/designers/BenefitEditorPage.test.tsx`（新，空列表 / 选中 / demo）

Slice 2：

- `src/features/operations/OperationsPage.tsx` — 发放 tab
- `src/features/operations/AwardIntentsPanel.tsx`（新）
- `e2e/console.spec.ts` — 不测真发奖，只测绑定区可见

### 权益 `benefit-console`（换仓执行）

- `src/api/types.ts`、`src/api/benefit.ts`
- `src/pages/CatalogPage.tsx` — 模板字段
- `src/pages/WalletsPage.tsx`、`WalletsPage.test.tsx`（新）
- `src/components/wallets/WalletDetailDrawer.tsx`（新）
- `src/components/layout/AppLayout.tsx` — 导航
- `src/router.tsx`
- `src/components/common/StatusTag.tsx`
- `e2e/console.smoke.spec.ts` — 窄屏打开 /wallets

### 对账 `recon-console`（Slice 3）

- `src/api/types.ts`、差异 Drawer 只读区

禁止改：`shared/data/demo.ts` 里加假券包；benefit 增加 AwardIntent 提交表单。

**改仓纪律（机械）：**

- Cursor 在本仓 **禁止** 改 `services/`、`platform-*`、`jobs/`、`marketing-contracts/`、Java 测试。
- Codex / Claude **禁止** 改 `frontend/`、`benefit-console/`、`recon-console/`。
- 权益前端在 `benefit-center` 仓做；对账前端在 `recon-platform` 仓做。

---

## 按依赖排序的实施步骤

```text
T0  用户批准本计划
T1  Codex/Claude：BACKEND_HANDOFF Slice 1（benefit 模板 + wallet API + AwardItem.walletEntryId + 测试）
T2  Cursor：benefit-console Catalog 扩展 + WalletsPage（benefit-center 仓）。可与 T3 并行
T3  Codex/Claude：营销 GET /benefit-skus + BenefitDefinition.benefitSkuId
T4  Cursor：营销 BenefitEditor SkuPicker（本仓 frontend/ 仅）
T5  Codex/Claude：Slice 2 AwardIntent outbox + 只读列表
T6  Cursor：营销 Operations 发放 tab；权益订单链到券包
T7  Codex/Claude：Slice 3 ODS
T8  Cursor：recon 差异字段
T9  Slice 4 另立计划（审批 / 风控 / 核销）
```

**T2 / T4 的硬门禁**：对应 OpenAPI 已合入且本地能 200/空数组。否则只改类型，不交未接通的页面。

营销前端热更新看 Vite `:4173`；Docker `:8084` 必须 `--build console`。权益 / 对账同理。

---

## 测试策略

### 营销

- Vitest：`SkuPickerField` 空 / 错误 / 选中；demoMode 不请求或请求失败有提示。
- Playwright：desktop 打开权益编辑器见到 SKU 区；`Pixel 7` 打开同一页，选择器可见且可滚动（**移动端验收**）。
- 不 e2e 真实发券。

### 权益

- Vitest：Catalog 相对/绝对有效期校验；Wallets 空 subject 不请求。
- Playwright：Pixel 5 打开 `/wallets` 为卡片布局（**移动端验收**）。

### 对账

- 沿用现有差异 / 补救测；新字段 snapshot 或 getByText。

回归：benefit `canRemediateItem` UNKNOWN 仍禁用；营销不出现发奖提交按钮。

---

## 验收标准

### 产品

- [ ] AC-01 Catalog 能保存带有效期与状态的模板，列表能读回。
- [ ] AC-02 指定 subject 能查出券包；无数据为空态而非报错。
- [ ] AC-03 营销 Benefit 能绑定 ACTIVE SKU；无模板时为空态。
- [ ] AC-04 任何控制台都没有「新建 AwardIntent」表单。
- [ ] AC-05 Slice 2：同一 `sourceRequestId` 能在营销发放 tab 与权益订单互查。

### 工程

- [ ] AC-06 前端无硬编码券包 / SKU 业务数据。
- [ ] AC-07 后端 Slice 1 含并发限额与缓存失效测试（Codex 证据）。
- [ ] AC-08 营销 Decision 路径仍不改券状态。

### 移动端（至少一个视口）

- [ ] AC-09 权益 `/wallets` 在 375×667（或 Pixel 5）为卡片而非撑破的 Table。
- [ ] AC-10 营销 `/designers/benefit` 在 390 宽下 SKU 区可读、不横向裁切主按钮。

---

## 风险与回滚

| 风险 | 缓解 | 回滚 |
|---|---|---|
| 后端契约延迟，前端先做空壳 | 门禁：无 OpenAPI 不合并页面 | 删路由 / feature flag |
| 营销与 drools 双发 | 租户开关 + 同一 sourceRequestId | 关 CENTER，回 SHADOW |
| 模板字段让旧 Catalog 500 | 后端兼容旧 JSON | 前端忽略未知字段 |
| 券包做成全表扫描 | 只按 subject 点查 | 下线 /wallets |
| 三高口号无测试 | BACKEND_HANDOFF 作为 Codex PR 检查单 | 不启用 CENTER |
| Docker 旧前端 | 文档写明要 rebuild console | 回滚镜像 tag |

---

## 给执行者的一页纸

**Codex / Claude** 打开 `BACKEND_HANDOFF.md`，从 Slice 1 做 benefit-center。不要改前端。

**Cursor（本仓）** 等 `GET /api/v1/benefit-skus` 与 `BenefitView.benefitSkuId` 可用后，只改 `BenefitEditorPage` + `AssetsPage` + schemas/client。

**Cursor（benefit-center 仓）** 等 SkuView / wallet API 可用后，改 `CatalogPage` 并新增 `WalletsPage`。

**不要**在营销画布里重做库存与对账。

后置与本阶段不做（2b Journey award、等价 SKU UI、C 端券包、解冻、权限拆、现金 / 开放平台 / 积分 / 超预算与补发 BPMN）见同目录 `DEFERRED.md`。批准某一刀之前不改代码。

联邦项目正常编排（人怎么切台、机器怎么流、本机启动顺序）见同目录 `FLOW.md`。
