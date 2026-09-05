# 决策记录：Slice 4a SKU 模板上线审批

日期：2026-09-05  
范围：SKU 模板首次上线的四眼审批。不是超预算、补发/冲正、风控或核销。  
执行分工：Cursor 只做控制台前端；Claude Code / Codex 做全部后端与 BPMN。

对照：

- 父计划 `docs/plans/benefit-marketing-alignment-0905-0949/`
- `workflow-platform/docs/onboarding-new-process.md`
- `workflow-platform/docs/integration-guide.md`

## 1. 问题

Slice 1 把 SKU 升级为模板，并在领域模型写好了 `DRAFT → PENDING_APPROVAL → ACTIVE`。现状：

- 后端已禁止 `DRAFT → ACTIVE`（`SkuTemplateStatus.canTransitionTo`）。
- Catalog 表单仍可手选 `ACTIVE`，保存会被拒绝。运营实际上**无法**把模板投放到营销 SkuPicker。
- benefit-center **没有** workflow outbox / 落地消费。
- workflow-console 待办中心硬编码 `hisRxReview`。

运营需要：保存草稿 → 提交上线审批 → 审批人在 workflow-console 办理 → 回执落地后模板变 ACTIVE → 营销才能绑定。

## 2. 备选方案

| 方案 | 做法 | 优点 | 代价 | 裁决 |
|---|---|---|---|---|
| A Catalog 提交 + workflow 办理 | benefit-console 只发「提交审批」；待办/办理留在 `:8302`；deep-link 轨迹 | 符合联邦控制台与 onboarding 配方 | 运营切一台 | **采用** |
| B 营销 Benefit 发布顺带审 SKU | 营销 `submit` 触发 SKU 审批 | 少一个入口 | SKU 权威在 benefit；与营销 `mk_approval_case` 双轨 | **否决** |
| C benefit-console 内嵌待办 | iframe / 自建 inbox | 少切台 | 违背「消费方不重建 inbox」；OIDC 域不同 | **否决** |
| D 继续 PUT 直改 ACTIVE | 改回状态机允许 DRAFT→ACTIVE | 前端零改 | 丢掉四眼，与已落地域模型冲突 | **否决** |

## 3. 推荐架构（已裁决）

```text
运营 benefit-console /catalog
  DRAFT 保存字段  → PUT /admin/v1/skus/{skuId}
  提交上线审批     → POST .../skus/{skuId}:submit-for-approval
                    同事务：DRAFT→PENDING_APPROVAL + outbox command.start.v1

审批人 workflow-console :8302
  待办 /tasks?definitionKey=benefitSkuGoLive
  办理 PASS/REJECT → 202 PENDING_BUSINESS（不得显示「已完成 / 已投放」）

benefit-center 落地
  消费 action.requested
  APPROVE → PENDING_APPROVAL→ACTIVE + 删 L1 模板缓存 + SKU_TEMPLATE_CHANGED
  REJECT  → PENDING_APPROVAL→DRAFT   + 不投放
  回执 action.applied.v1

营销 BenefitEditor
  仍只拉 GET /api/v1/benefit-skus?status=ACTIVE
  Slice 4a 零改动
```

权威源：

| 对象 | 权威 | 其它系统 |
|---|---|---|
| 模板字段与生命周期 | benefit `SkuTemplate` | 营销只引用 ACTIVE |
| 审批待办 / 办理 | workflow-platform | benefit 不存任务副本 |
| 是否已投放 | benefit `status`（回执落地后） | 前端禁止把 202 当 ACTIVE |

## 4. 状态机（4a 必须改的一处）

现网只允许 `PENDING_APPROVAL → ACTIVE`，驳回会卡死。4a **扩展**：

```text
DRAFT → PENDING_APPROVAL → ACTIVE ↔ PAUSED → RETIRED
                 ↓ REJECT / 仅回执通道
               DRAFT
```

规则：

- 运营 `PUT` **不能** 写 `PENDING_APPROVAL` 或从 `DRAFT` 写 `ACTIVE`。
- `PENDING_APPROVAL → DRAFT` 只允许落地回执 `SKU_GO_LIVE_REJECT`，禁止客户端 PUT。
- `PENDING_APPROVAL` 期间字段冻结（`SKU_APPROVAL_LOCKED`）。
- `PAUSED → ACTIVE` **不**走 4a（已通过首次上线，运营暂停/恢复）。
- 关闭 `enabled=true` 遗留直跳 ACTIVE 的兼容路径。
- 4a **不做**撤回接口，避免孤儿流程实例；卡住时由审批人驳回。

## 5. 视觉与端形态

- 不统一三套 UI。benefit 继续 Ant `#0F6F6A`；workflow 继续 `#315EFB`；营销不改。
- Catalog 复用 `SkuStatusTag`「待审批」warning，不新做色板。
- 办理文案抄 workflow「已受理」，禁止「已完成」。
- 桌面内部运营台。窄屏：能看见待审批 + 打开全宽 Drawer；不要求在手机上完成审批配置。

## 6. 后端三高（沿用父计划 §6）

每一刀必须：租户隔离、写状态与 outbox 同事务、外部 HTTP/Kafka 在事务外、多副本 CAS、无单点内存真值。  
发起四元组幂等；落地按 `eventId` + `actionId` 双层幂等。  
模板变 ACTIVE 后删 L1 + 出新 version 语义保持 Slice 1：已发券不改有效期。

## 7. 明确不改 / 不臆造

- 超预算、补发/冲正 BPMN（留给后续 4a+ / 4x）。
- risk-platform check、wallet freeze/redeem/refund。
- 营销 GovernancePage / `mk_approval_case` 与 SKU 打通。
- benefit / marketing 自建审批 inbox。
- 任何控制台的 AwardIntent 提交表单。
- 合并 SPA、抽跨仓 UI 包。
- 前端硬编码审批任务或假 SKU 列表。

## 8. 待用户确认的假设

1. `processDefinitionKey = benefitSkuGoLive`；候选组 `BENEFIT_SKU_REVIEWER`；action `SKU_GO_LIVE_APPROVE` / `SKU_GO_LIVE_REJECT`。
2. `businessKey = skuId`；`idempotencyKey = {tenantId}|{skuId}|{skuVersion}`（同一版本一轮）。
3. 提交与办理权限分离：提交仍 `benefit.admin`；办理靠 workflow 候选组，不新增 `benefit.sku.submit`。
4. 营销 Slice 4a 零前端改动。
5. workflow-console 小改：待办读 `?definitionKey=&businessKey=`，且 **`definitionKey` 驱动 candidateGroup**（`benefitSkuGoLive` → `BENEFIT_SKU_REVIEWER`；默认仍 `hisRxReview` → `PHARMACIST`）。`ReviewDrawer` 确认文案按流程泛化，不再写死「审方」。
6. Catalog deep-link：`/catalog?skuId=` 打开对应 Drawer（对照订单页的 query 预填，参数名固定 `skuId`）。
7. 4a 不做撤回、不做 PAUSED 再审、不做营销 pending 提示。
8. workflow-platform-core **允许小改**：泛化 `MessageCorrelationService` 的 ACK message 名、新增 SKU outbox delegate、`BpmnAutoDeployer` 为 benefit 租户部署。禁止大改 Flowable 抽象层。
