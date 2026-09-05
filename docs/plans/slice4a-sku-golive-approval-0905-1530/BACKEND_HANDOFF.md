# 后端执行说明（Claude Code / Codex）— Slice 4a

Cursor **不改**这些仓库的 Java / OpenAPI / 迁移 / BPMN。本文件是后端改造合同。

只做 **SKU 模板首次上线审批**。不要顺手做超预算、补发/冲正、risk check、核销。

工作区：

| 仓库 | 角色 |
|---|---|
| `/Users/liruijun/personal/LLM/benefit-center` | 消费方：提交命令、状态机、outbox 发起、落地回执 |
| `/Users/liruijun/personal/LLM/workflow-platform` | 中台：BPMN + **必要的小改**（SKU delegate、ACK message 泛化、benefit 租户部署）。禁止大改 Flowable 抽象层 |
| `/Users/liruijun/personal/LLM/marketing-lowcode-platform` | **本刀不改后端**（ACTIVE 投影已存在） |

同步契约：benefit OpenAPI + 集成测试。失败一律 RFC 9457 Problem Details。写操作必须 `Idempotency-Key`。时间 ISO-8601。

三高 / DDD / 缓存总则仍遵守父文件  
`marketing-lowcode-platform/docs/plans/benefit-marketing-alignment-0905-0949/BACKEND_HANDOFF.md` §0。

---

## 0. 禁止事项

- 改 `benefit-console/`、`workflow-console/`、营销 `frontend/`。
- 在 benefit 里实现待办列表或 `complete-review`。
- 允许客户端 `PUT` 把 `DRAFT` 写成 `ACTIVE` 或 `PENDING_APPROVAL`。
- 保留 `enabled=true` 遗留直跳 ACTIVE。`JdbcCatalogAdminService` **insert（`command.status()==null && enabled`）与 update（`targetStatus`）两处都要关**。
- 复用 `RxReviewActionOutboxDelegate` 发 SKU action（它写死 `RX_REVIEW_*`）。
- 继续让 `MessageCorrelationService` 只认 `hisRxReviewApplied`（SKU 回执将永远关联不上）。
- 新造审批表当任务库（流程实例在 workflow）。
- 把 4b/4c 塞进本 PR。

---

## 1. 状态机

文件：`benefit-domain/.../SkuTemplateStatus.java` 及 `SkuTemplateStatusTest`。

现网 `SkuTemplateStatusTest` **断言** `PENDING_APPROVAL → DRAFT` 为 false。4a 必须改域 + **翻转该断言**（预期红，不是回归事故）。

```text
DRAFT → PENDING_APPROVAL → ACTIVE ↔ PAUSED → RETIRED
                 ↓ REJECT / 仅回执通道
               DRAFT
```

| 迁移 | 谁可以 | 谁不可以 |
|---|---|---|
| DRAFT → PENDING_APPROVAL | `POST :submit-for-approval` | PUT body.status |
| PENDING_APPROVAL → ACTIVE | 落地 `SKU_GO_LIVE_APPROVE` | PUT / 前端 |
| PENDING_APPROVAL → DRAFT | 落地 `SKU_GO_LIVE_REJECT` | PUT / 前端 |
| PENDING 改字段 | 无人 | PUT → `SKU_APPROVAL_LOCKED` |
| PAUSED → ACTIVE | 现有运营 PUT（不走审批） | — |
| enabled 派生 | 仅 ACTIVE ⇒ true | 客户端 enabled 忽略 |

已发出的 `WalletEntry` 仍绑 `skuVersion`，不随本次上线改有效期。

---

## 2. OpenAPI（先合契约）

扩展 `benefit-contract/.../benefit-center-v1.yaml`：

```
POST /admin/v1/skus/{skuId}:submit-for-approval
  security: benefit.admin
  header: Idempotency-Key (required)
  body: { expectedVersion: integer }
  202 SkuSubmitAcceptance { skuId, status: PENDING_APPROVAL, version }
  409 Problem:
    SKU_NOT_DRAFT
    SKU_VERSION_CONFLICT
    SKU_SUBMIT_IN_FLIGHT
    SKU_ILLEGAL_TRANSITION

PUT /admin/v1/skus/{skuId}
  PENDING_APPROVAL → 409 SKU_APPROVAL_LOCKED
  客户端 status∈{PENDING_APPROVAL, ACTIVE} 且当前 DRAFT → 409 SKU_ILLEGAL_TRANSITION

SkuView 可选（仅 PENDING 时有值，其它状态 null，不要造「—」）:
  approvalProcessDefinitionKey: benefitSkuGoLive
  approvalBusinessKey: skuId
```

错误码必须进 `BenefitErrorCode` + `BenefitExceptionAdvice`。今天 `IllegalStateException` 一律映射 `INTERNAL_ERROR`，前端无法机器处理。4a 新增并映射：

```text
SKU_NOT_DRAFT
SKU_VERSION_CONFLICT
SKU_APPROVAL_LOCKED
SKU_ILLEGAL_TRANSITION
SKU_SUBMIT_IN_FLIGHT
```

`GET /admin/v1/skus?status=PENDING_APPROVAL` 若尚无过滤，4a 可只靠列表里的 status 字段；有现成 status query 则沿用。

营销 `GET /api/v1/benefit-skus?status=ACTIVE` **不要改**；落地 ACTIVE 后自然可见。

---

## 3. 发起（benefit-center）

同事务：

1. 校验 DRAFT、`expectedVersion` CAS、字段完整（CASH 面额等沿用现规则）。
2. `status = PENDING_APPROVAL`，version + 1。
3. 写 outbox `workflow.command.start.v1`，**禁止**业务事务里 HTTP 调 workflow。

`StartProcessCommandV1`：

```text
processDefinitionKey = benefitSkuGoLive
businessKey          = skuId
idempotencyKey       = {tenantId}|{skuId}|{skuVersion提交前}
initiator            = JWT sub
source               = benefit-center          # 登记 WORKFLOW_KAFKA_SOURCE_TENANT_BINDINGS
variables 白名单（只快照，审批人只读）:
  skuId, skuVersion, benefitType,
  faceValueMinor, currency,
  validityType, validFrom, validTo, relativeDays
```

四元组幂等：同 key 重放返回首次 202，不启第二实例。  
同 key 不同 payload → 409。  
Kafka / HMAC / tenant binding 按 `workflow-platform/docs/integration-guide.md` §6。

Relay 在事务外投递。ACK 不明禁止自动换 key 重发。

---

## 4. 中台（workflow-platform）

可靠消息骨架已有。4a **必须**做下列小改，否则 T4 冒烟过不了。禁止大改 Flowable 抽象层。

1. 新增 classpath BPMN（含 BPMNDI）：
   `workflow-platform-core/src/main/resources/bpmn/benefit-sku-golive-v1.bpmn20.xml`
2. `processDefinitionKey = benefitSkuGoLive`
3. 主人工任务 id：`skuGoLiveReview`；候选组：`BENEFIT_SKU_REVIEWER`（大写无前缀）
4. ACK wait message 名：`benefitSkuGoLiveApplied`（不要复用 `hisRxReviewApplied`）
5. **新增** `@Component("skuGoLiveActionOutboxDelegate")`，emit
   `SKU_GO_LIVE_APPROVE` / `SKU_GO_LIVE_REJECT`。
   **禁止** BPMN 引用 `${rxReviewActionOutboxDelegate}`（它写死 `RX_REVIEW_*`）。
6. **泛化** `MessageCorrelationService`：按 `processDefinitionKey`（或 BPMN messageRef）解析 ACK message，不要只认 `hisRxReviewApplied`。HIS 路径保持原 message 名。
7. **部署**：更新 `BpmnAutoDeployer`（或等价 admin 脚本）为 **benefit 租户**（如 `dev-tenant` / JWT audience）部署该 BPMN。只部署 `tenant=his` 时 benefit 待办永远空。
8. HIS `hisRxReview` 金测 / `RxReviewLoopTest` 不得回归。

消费方不引私服 SNAPSHOT 也能工作：用 protocol 记录自己实现 Kafka 编解码即可；若引 SDK，写明本地 Maven 仓库前提。

---

## 5. 落地（benefit-center）

新 inbox consumer（自有 group，例如 `benefit-wf-sku-golive`）：

1. 去重 `eventId`。
2. 按 `actionId` 幂等做副作用：
   - `SKU_GO_LIVE_APPROVE`：CAS `PENDING_APPROVAL → ACTIVE`；删 L1 SkuTemplate 缓存；写 `SKU_TEMPLATE_CHANGED`（已有 outbox 可复用）。
   - `SKU_GO_LIVE_REJECT`：CAS `PENDING_APPROVAL → DRAFT`；不清已发券（此时不应有新发券）。
3. 同事务写 outbox `workflow.action.applied.v1`（`APPLIED`）。
4. 业务拒绝（状态已不是 PENDING）→ `REJECTED_BY_BUSINESS`，**不要**再改库。
5. 渠道 / HTTP 不进该事务。

禁止：落地时按「当前规则」重算面额；禁止同步回调营销。

---

## 6. 必须使用的模式

| 模式 | 用在哪 |
|---|---|
| Outbox + Relay | start 与 applied |
| Inbox + 幂等 | action.requested |
| Specification | `SkuTemplateStatus` 迁移 |
| CAS / 乐观锁 | expectedVersion、落地状态 |
| Cache-Aside | ACTIVE 后删 L1；禁止缓存「审批中」当已投放 |
| Circuit Breaker | Kafka 生产失败入重试/DLQ，不回滚已提交 PENDING（靠 replay） |

领域层零 Spring / Kafka。ArchUnit 继续挡依赖方向。

---

## 7. 测试（PR 必须带）

- 域：翻转 `SkuTemplateStatusTest`：`PENDING → DRAFT` 为 true，但 HTTP PUT 仍拒绝；仅回执通道可走。
- 错误码：409 必须是上列 `SKU_*`，不是 `INTERNAL_ERROR`。
- 部署：benefit 租户能查到 `benefitSkuGoLive` 定义。
- 关联：PASS 后 `action.applied` 必须推进流程（证明 ACK message 已泛化）。
- HTTP：DRAFT 提交 202；再提交同 Idempotency-Key 202 且只有一个流程；不同 body 409。
- HTTP：PUT DRAFT→ACTIVE → `SKU_ILLEGAL_TRANSITION`。
- HTTP：PENDING 改字段 → `SKU_APPROVAL_LOCKED`。
- 集成（参照 `RxReviewLoopTest`）：start → PASS → SKU ACTIVE → L1 不再把旧 DRAFT 当可领；REJECT → DRAFT。
- 落地重放同一 `actionId` 不二次升 ACTIVE。
- 关闭 `enabled` 遗留路径的回归。
- `hisRxReview` 金测 / 现有 Slice1 E2E 不得红。
- 并发双提交同一 version：只有一个 PENDING / 一个 start 事件。

本地 Compose 数字不得写成 SLO。

---

## 8. 配置与运维

文档写进 benefit-center README 小节：

```text
workflow.kafka.enabled
source = benefit-center
WORKFLOW_KAFKA_SOURCE_TENANT_BINDINGS
候选组 BENEFIT_SKU_REVIEWER 需在 Casdoor 配给审批人
开关默认：本地可开；生产需显式开 consumer
回滚：停 benefit workflow consumer + 停 start relay；在途 PENDING 人工在 workflow 驳回
```

---

## 9. 做完后通知 Cursor

只发这些（不要改 console）：

```text
POST /admin/v1/skus/{skuId}:submit-for-approval
  202 字段：skuId, status, version
SkuView 新字段：approvalProcessDefinitionKey, approvalBusinessKey
错误码：SKU_NOT_DRAFT, SKU_VERSION_CONFLICT, SKU_APPROVAL_LOCKED,
        SKU_ILLEGAL_TRANSITION, SKU_SUBMIT_IN_FLIGHT
scope：仍 benefit.admin
BPMN：benefitSkuGoLive / skuGoLiveReview / BENEFIT_SKU_REVIEWER
ACK message：benefitSkuGoLiveApplied
action：SKU_GO_LIVE_APPROVE | SKU_GO_LIVE_REJECT
delegate：skuGoLiveActionOutboxDelegate（禁止复用 rxReview）
```

---

## Codex 工作方式

1. 先合 OpenAPI + 失败用例，再实现。
2. 一次只做 4a。
3. 不改任何 frontend / *-console。
4. PR 必须含 ArchUnit 或模块边界证明 + 上列测试。
5. 完成后把 §9 字段表贴回 Cursor 会话。
