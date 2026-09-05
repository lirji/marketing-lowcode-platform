# 执行计划：Slice 4a SKU 模板上线审批

日期：2026-09-05  
分工：

| 角色 | 仓库 | 做什么 |
|---|---|---|
| **Claude / Codex** | benefit-center 后端、workflow-platform BPMN | 见同目录 `BACKEND_HANDOFF.md` |
| **Cursor** | benefit-console、workflow-console | 本文前端章节；**接口未到不写页面假数据** |

决策见 `DECISION_RECORD.md`。父计划：`docs/plans/benefit-marketing-alignment-0905-0949/`。

---

## Goals

1. 运营能把 DRAFT 模板提交上线审批；审批通过后模板变为 ACTIVE，营销 SkuPicker 才能绑到。
2. 待办与办理只发生在 workflow-console；benefit-console 只改状态展示与「提交审批」。
3. 办理恒为 202「已受理」；ACTIVE 只在 benefit 消费回执之后出现。
4. 驳回回到 DRAFT，运营可改字段再提交。
5. 后端按三高 + outbox + 幂等实现（后端合同，不由 Cursor 落地）。

## Non-goals

- 超预算、补发/冲正、风控 check、核销。
- 营销 BenefitEditor / GovernancePage / AwardIntent 任何改动。
- benefit-console 或 marketing 自建待办 inbox。
- 撤回审批、PAUSED→ACTIVE 再审、`benefit.sku.submit` 新 scope。
- 合并 SPA；AwardIntent 提交表单；硬编码 SKU / 任务 Mock。
- 把 Catalog 或 workflow 设计器做成手机可编辑工作台。

---

## 视觉方向与设计参考

**沿用既有语言，不重做视觉。**

| 控制台 | 依据 | Tokens |
|---|---|---|
| 权益 | `benefit-console/src/theme/theme.ts`、`colors.ts` | Ant 主色 `#0F6F6A`，radius 8/12，warning `#D97706` |
| 审批 | `workflow-console/src/theme/colors.ts` | Ant 主色 `#315EFB`，radius 8/12 |
| 营销 | 不改 | — |

Catalog 状态用已有 `SkuStatusTag`：`PENDING_APPROVAL` →「待审批」warning。  
办理反馈抄 `ReviewDrawer` / `RecentReviews`：info「已受理」，PhaseTag「处理中 / 已落地」，禁止 success「已完成」。  
参考站只核对 B 端「目录 + 抽屉 + 外链轨迹」（SaaS Interface），不引入新色板。

---

## 路由与页面流

```text
运营 benefit-console /catalog
  → 新建/编辑 DRAFT（字段仍 PUT）
  → Drawer 主按钮「提交上线审批」（不是状态下拉选 ACTIVE）
  → 成功：列表 SkuStatusTag=待审批；Drawer 只读
  → 若配了 workflowConsoleOrigin：外链
        轨迹 /process/benefitSkuGoLive?businessKey={skuId}
        待办 /tasks?definitionKey=benefitSkuGoLive&businessKey={skuId}
     origin 空则只展示可复制 skuId

审批人 workflow-console :8302
  → /tasks?definitionKey=benefitSkuGoLive
  → ReviewDrawer PASS/REJECT → message.info 已受理
  → RecentReviews 轮询 phase，COMPLETED 才叫已落地

运营刷新 Catalog
  → 通过：ACTIVE「已投放」
  → 驳回：DRAFT，可再编辑提交

营销 /designers/benefit
  → 无 4a UI；ACTIVE 出现后 SkuPicker 自然多一项
```

### 路由变更

**benefit-console**

| 路径 | 动作 |
|---|---|
| `/catalog` | 扩展 Drawer 与状态机按钮，不改路径 |
| `/catalog?skuId=` | **新增**：打开对应 SKU Drawer（对照 `/orders?q=`） |

导航不加「审批」一级菜单。

**workflow-console**

| 路径 | 动作 |
|---|---|
| `/tasks` | 读 `definitionKey`（默认仍 `hisRxReview`）、`businessKey` |
| `/process/{key}?businessKey=` | 已有，不改 |

**marketing console**

无路由变更。

---

## 组件树

### 权益（复用 vs 新建）

```
CatalogPage                         复用，拆出状态机动作
  PageHeader / AsyncState           复用
  SkuStatusTag                      复用（待审批已映射）
  SkuDrawer                         从 CatalogPage 抽出（现有 Drawer 过长）
    字段 Form                       复用 antd rules
    状态：只读 Tag，不再用手选 ACTIVE
    SubmitApprovalButton            新建：仅 DRAFT + canWrite
    ApprovalHint                    新建：PENDING 只读 + 可选外链
  workflowHref()                    新建 utils，仿营销 benefitOrderHref
```

### 审批台

```
TasksPage                           改：definitionKey / businessKey 来自 URL
  TaskCard / ReviewDrawer           复用 PASS/REJECT
  ReviewDrawer 确认文案             按 definitionKey 泛化，去掉写死「审方」
RecentReviews / PhaseTag            复用，不改语义
```

### 营销

不新建、不改。

---

## 状态与边界（逐页）

### Catalog / SkuDrawer

| 态 | 行为 |
|---|---|
| loading | 现有 `PageSkeleton` |
| empty | 现有「尚未创建模板」 |
| error | `ErrorState` + 重试；映射 `SKU_NOT_DRAFT`、`SKU_VERSION_CONFLICT`、`SKU_APPROVAL_LOCKED`、`SKU_ILLEGAL_TRANSITION` |
| DRAFT + 可写 | 字段可编；主按钮「保存草稿」；次按钮「提交上线审批」（需先有 skuId / version） |
| 新建未保存 | 只有保存草稿，提交 disabled，title「请先保存草稿」 |
| PENDING_APPROVAL | 字段 disabled；无提交；Alert info「已提交上线审批，办理在流程中台」；外链仅 origin 非空时渲染 |
| ACTIVE / PAUSED / RETIRED | 与 Slice 1 相同：ACTIVE↔PAUSED、RETIRED 只读终态；**无**「提交审批」 |
| 提交中 | 按钮 pending，防连点；必须带 `Idempotency-Key` |
| 提交成功 | `message.info('已提交审批')`（不是「已投放」）；**不**关 Drawer（与保存草稿的 `setSkuOpen(false)` 区分）；用 202 body 刷新本地 `version`；切只读 |
| 无写权限 | 按钮 disabled |
| 窄屏 | 现有 Alert「建议桌面配置」+ 全宽 Drawer；**只读也可点卡片打开 PENDING**（去掉 `!canWrite` 才 onClick）；提交按钮仍需 write，`minHeight: 44` |
| deep-link 无此 skuId | Alert「未找到该模板」，不造假行 |

### workflow TasksPage

| 态 | 行为 |
|---|---|
| 无 definitionKey | 默认 `hisRxReview`（兼容试点） |
| 指定 benefitSkuGoLive | 按该 key 拉待办；**candidateGroup=`BENEFIT_SKU_REVIEWER`**；只展示主 UserTask（如 `skuGoLiveReview`），排除 `manualRepair` / `pharmacistReview` |
| empty | 「暂无待办」 |
| 办理成功 | 已有「已受理,待业务落地」 |
| 409 | 已有「已被处理或状态已变更」 |

### 营销 BenefitEditor

不改。PENDING 模板不会出现在 `status=ACTIVE` 列表。

---

## API 契约（前端只消费）

后端未合并前，前端禁止用常量数组冒充任务或假 ACTIVE。

### benefit-center

```
PUT  /admin/v1/skus/{skuId}
     仅允许新建或更新 DRAFT 字段；禁止客户端写 PENDING_APPROVAL / DRAFT→ACTIVE
     对 PENDING_APPROVAL 返回 409 SKU_APPROVAL_LOCKED
     必须 Idempotency-Key + expectedVersion

POST /admin/v1/skus/{skuId}:submit-for-approval
     header: Idempotency-Key, Authorization
     body:   { expectedVersion }
     202 { skuId, status: PENDING_APPROVAL, version }
     409 SKU_NOT_DRAFT | SKU_VERSION_CONFLICT | SKU_SUBMIT_IN_FLIGHT

GET  /admin/v1/skus
     SkuView.status 已含 PENDING_APPROVAL
     4a 增可选：
       approvalProcessDefinitionKey?: string   # PENDING 时为 benefitSkuGoLive
       approvalBusinessKey?: string            # = skuId
```

权限：仍 `benefit.admin`。

### workflow（已有，前端消费）

```
GET  /api/v1/tasks?definitionKey=&businessKey=&candidateGroup=
POST /api/v1/tasks/{id}/complete-review   → 202 PENDING_BUSINESS
GET  /api/v1/process-instances?definitionKey=&businessKey=
```

benefit-console **不**调这些接口。

### 运行时

benefit-console 增可选 `workflowConsoleOrigin`，来自 `VITE_WORKFLOW_CONSOLE_ORIGIN`（+ Docker `ARG` 如需要）。**4a 不引入** marketing 式运行时 `config.js`（benefit-console 没有这套注入）。默认空；空则不渲染跨台 `<a>`。

错误码前端要映射：`SKU_NOT_DRAFT`、`SKU_VERSION_CONFLICT`、`SKU_APPROVAL_LOCKED`、`SKU_ILLEGAL_TRANSITION`、`SKU_SUBMIT_IN_FLIGHT`。

---

## 响应式与移动端

**定性：桌面内部运营台。** 审批办理以 workflow 桌面为准；Catalog 窄屏只要求能看状态。

| 台 | 断点 | 小屏策略 |
|---|---|---|
| 权益 | 768 卡片 / 992 藏 Sider | Catalog SKU 继续 `mobile-data-card` + 全宽 Drawer；提交 44px |
| 审批 | 992 卡片 + 底抽屉 | 沿用 `TaskCard` / `ReviewDrawer`；不新断点 |
| 营销 | 不改 | — |

触控：benefit/workflow 主按钮 ≥44px。无 hover-only 新交互。不新增 WebView / 微信适配。  
路由 Tab 窄屏仍 Table：4a **不修**（非本切片）。

---

## 文件级改动清单（Cursor）

### 权益 `benefit-center/benefit-console`

- `src/api/types.ts` — `approvalProcessDefinitionKey` / `approvalBusinessKey`
- `src/api/client.ts` — **先**为所有 admin 写操作（至少 `saveSku` / `saveRoute` / `saveTenant`）注入 `Idempotency-Key`（现网缺 header，PUT 会 400）
- `src/api/benefit.ts` — `submitSkuApproval`
- `src/shared` 或 `src/config` — `workflowConsoleOrigin`
- `src/pages/CatalogPage.tsx` — 状态机 UI + `?skuId=`
- `src/components/catalog/SkuDrawer.tsx`（新，从 CatalogPage 抽出）
- `src/utils/workflowHref.ts`（新）
- `src/pages/CatalogPage.test.tsx` — DRAFT 可提交、PENDING 只读、无手选 ACTIVE
- `src/components/common/StatusTag.test.tsx` — 待审批文案
- `e2e/console.smoke.spec.ts` — Pixel 5 打开 `/catalog` 见待审批卡片（mock API）

禁止：假 SKU 列表；complete-review 调用；AwardIntent 表单。

### 审批 `workflow-platform/workflow-console`

- `src/pages/TasksPage.tsx` — URL `definitionKey` / `businessKey`；`definitionKey` 映射 candidateGroup；SKU 只展示 `skuGoLiveReview`
- `src/hooks/useTasks.ts` — 透传 query
- `src/components/domain/ReviewDrawer.tsx` — 确认标题/字段标签按流程泛化（去掉「审方」「就诊」）
- `RecentReviews` / `PageHeader` — 描述跟 URL `definitionKey`，不写死 HIS
- 对应 Vitest：URL 筛选 + 202 文案不回退

### 营销

不改。

**改仓纪律（机械）：**

- Cursor **禁止** 改 Java / OpenAPI / 迁移 / BPMN XML。
- Codex / Claude **禁止** 改 `benefit-console/`、`workflow-console/`、营销 `frontend/`。

---

## 按依赖排序的实施步骤

```text
T0  用户批准本计划
T1  Codex：BACKEND_HANDOFF（状态机 REJECT→DRAFT 并改 SkuTemplateStatusTest、
    submit API + BenefitErrorCode、outbox、落地、SKU delegate、
    泛化 ACK message、benefit 租户部署 BPMN、关 enabled 直跳、测试）
T1b Cursor（可与 T1 并行）：benefit-console 所有 admin 写请求补 Idempotency-Key
    （不依赖新 API；修现网 PUT 400）
T2  Cursor：workflow-console TasksPage
    definitionKey / businessKey / candidateGroup 映射（可与 T1 后半并行）
T3  Cursor：Catalog 提交审批 + ?skuId= + PENDING 只读
    （OpenAPI 合入 且 saveSku PUT 本地 204 且 submit 能 202/409 后再接通按钮）
T4  冒烟：DRAFT 提交 → 待办 → PASS → Catalog ACTIVE → 营销 SkuPicker 可见
    以及 REJECT → 回到 DRAFT
```

**T3 硬门禁**：`POST :submit-for-approval` 已在 OpenAPI，本地 submit 能打到 202 或稳定 409，**且** `saveSku` 带 Idempotency-Key 的 PUT 为 204。否则只改类型，不合并未接通按钮。

域单测 `PENDING_APPROVAL.canTransitionTo(DRAFT)` 现为 `false`，T1 **会翻红后改断言**，属预期，不是回归事故。

benefit Docker 镜像必须 `--build console`；本地 Vite 热更新即可。

---

## 测试策略

### 权益 console

- Vitest：DRAFT 显示提交；PENDING 字段 disabled 且无「已投放」toast；状态下拉不再含 ACTIVE 作为草稿出口；`workflowConsoleOrigin` 空不渲染 `<a>`。
- Playwright：Pixel 5 打开 `/catalog`，mock 一条 `PENDING_APPROVAL`，卡片可见「待审批」，Drawer 主按钮 ≥44px（**移动端验收**）。

### 审批 console

- Vitest：`?definitionKey=benefitSkuGoLive` 不再写死 hisRxReview；办理仍「已受理」。
- 不强制新 E2E（现网仅 Desktop Chrome）。

### 营销

- 回归现有 BenefitEditor 4 条：仍只请求 `ACTIVE`。不新写 4a 用例。

### 后端（Codex 证据）

见 BACKEND_HANDOFF §测试。前端不替代。

---

## 验收标准

### 产品

- [ ] AC-01 DRAFT 模板能提交上线审批，列表变为「待审批」。
- [ ] AC-02 Catalog **不能**再把草稿手选成 ACTIVE。
- [ ] AC-03 PENDING 期间不能改模板字段。
- [ ] AC-04 workflow-console 办理显示「已受理」，不是「已投放」。
- [ ] AC-05 PASS 落地后 Catalog 为 ACTIVE；营销 SkuPicker 能选到该模板。
- [ ] AC-06 REJECT 落地后 Catalog 回到 DRAFT，可再提交。
- [ ] AC-07 origin 为空时无跨台死链。
- [ ] AC-08 任何控制台仍没有「新建 AwardIntent」表单。

### 工程

- [ ] AC-09 前端无硬编码审批任务 / SKU 业务数据。
- [ ] AC-10 后端含：非法迁移、同 version 双提交幂等、回执重复消费、缓存失效（Codex 证据）。
- [ ] AC-11 `enabled=true` 遗留路径不能再直跳 ACTIVE（**insert 与 update 两处**都要关）。

### 移动端（至少一个视口）

- [ ] AC-12 benefit `/catalog` 在 Pixel 5（或 375×667）能看到待审批卡片，Drawer 全宽，提交/关闭按钮 ≥44px。

---

## 风险与回滚

| 风险 | 缓解 | 回滚 |
|---|---|---|
| 契约未到，前端先做空壳 | T3 门禁：无 OpenAPI 不接通按钮 | 隐藏提交按钮 |
| 驳回卡在 PENDING | 领域允许回执通道 PENDING→DRAFT | 运维手工改库（最后手段） |
| 双开流程 | idempotencyKey=tenant\|sku\|version | 关 consumer |
| TasksPage 改坏 hisRxReview | 默认 key 仍 hisRxReview | 回滚 console 该文件 |
| 202 被当成已投放 | 文案与测试钉死 | 改 copy |
| 遗留 enabled 直跳 | 4a 关闭 insert + update 两处 | 保持关闭 |
| ACK message 仍写死 hisRxReviewApplied | T1 必须泛化，否则 PASS 后流程卡死 | 回滚 consumer |
| 待办 candidateGroup 仍 PHARMACIST | T2 按 definitionKey 映射 | 回滚 TasksPage |
| Docker 旧前端 | 文档写明 rebuild console | 回滚镜像 tag |

---

## 给执行者的一页纸

**Codex / Claude** 打开同目录 `BACKEND_HANDOFF.md`，做 benefit-center 消费方 + workflow 小改（delegate / ACK / 租户部署）。不要改任何 console。

**Cursor（benefit-console）** 先补 Idempotency-Key（T1b）；等 `:submit-for-approval` 可用后改 Catalog：去掉手选 ACTIVE，加提交审批与 `?skuId=`。

**Cursor（workflow-console）** 待办按 `definitionKey` 换 candidateGroup，办理文案去「审方」硬编码。

**不要**在营销画布或 benefit-console 里做审批 inbox。
