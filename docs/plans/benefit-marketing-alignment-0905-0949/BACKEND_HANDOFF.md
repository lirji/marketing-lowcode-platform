# 后端执行说明（Claude Code / Codex）

Cursor **不改**这些仓库的 Java / OpenAPI / 迁移。本文件是后端改造合同。

对照差距文档第 6 节顺序，加上三高 / DDD / 多层缓存硬约束。

工作区：

| 仓库 | 角色 |
|---|---|
| `/Users/liruijun/personal/LLM/benefit-center` | 模板、券包、履约、用户级限额 |
| `/Users/liruijun/personal/LLM/marketing-lowcode-platform` | 绑 SKU、场景出 `AwardIntent`、应发事实 |
| `/Users/liruijun/personal/LLM/recon-platform` | ODS、三方/履约对账、纠错命令 |
| `/Users/liruijun/personal/LLM/drools-demo` | 过渡期 AwardIntent 连接器，禁止再扩主链路 |
| `/Users/liruijun/personal/LLM/workflow-platform` | Slice 4 审批 |
| `/Users/liruijun/personal/LLM/risk-platform` | Slice 4 发放前 check |

同步契约：各仓 OpenAPI / AsyncAPI + 集成测试。失败一律 RFC 9457 Problem Details。写操作必须 `Idempotency-Key`。时间 ISO-8601。金额 `long` 最小货币单位，禁止 `double`。

---

## 0. 三高与架构门禁（每一刀都要过）

### 0.1 高并发

- 业务表主键 / 唯一键以 `tenant_id` 开头；查询必须带租户谓词。
- API 与 worker 连接池、线程池、Kafka 消费者分配额；按租户 bulkhead，禁止一个热点租户打满全局池。
- 库存 / 预算用条件更新 + 分桶（`bucket_no`），禁止单行热点 SKU 硬碰整账户。
- 列表接口强制 `limit`（默认 20，最大 50）+ seek 分页，禁止 `SELECT *` 无界。
- 领取 / 决策路径：本地无锁或单 key 限流（用户 + sku + 日），Redis 令牌桶只做预检，账本仍以 DB CAS 为准。

### 0.2 高可用

- 写业务状态与 outbox **同一本地事务**；渠道 / 支付 / 外部 HTTP **事务外**。
- 多实例 worker：`lease_owner + lease_until + version` CAS 抢占；过期 SENDING 可恢复；旧 worker 提交必须失败。
- UNKNOWN：只查询同一 `operationNo`，禁止 fallback / 补发 / 换路由。
- 无单实例内存真值。进程缓存只做加速，重启必须能从 DB / Redis / 签名制品恢复。
- 健康检查区分 liveness（进程）与 readiness（依赖不可用则摘流）。决策面无制品时 readiness=false，不接新流量。

### 0.3 高性能

读路径延迟目标（本地 Compose 不作为生产结论，但实现必须按这个分层）：

| 路径 | 目标 | 禁止 |
|---|---|---|
| 营销 Offer evaluate | 纯函数 + 进程内制品，p99 低十毫秒级 | 同步访问控制库、画像库、benefit 库 |
| 领取资格预检 | L1 + L2 命中后不打模板表 | 每次领券 JOIN 模板 + 活动 + 库存 |
| 券包查询 | 用户维度聚簇，点查 | 扫发放订单反推券包 |
| 履约受理 | 单分片事务 | 跨库同步事务、XA |

### 0.4 多层缓存（必须落地，不是口号）

```text
L1 进程内 Caffeine
  - SkuTemplate 已投放版本（key = tenant + skuId + version）
  - 签名公钥 / JWKS
  - 路由表（tenant + skuId）
  TTL 短（10–30s）或 generation 失效；最大条目有界；禁止缓存余额。

L2 Redis
  - 用户日限额计数（tenant + subject + sku + yyyyMMdd）
  - 决策 / 领取幂等（sourceRequestId）
  - 券包摘要（tenant + subject）可选，写后删
  - Audience membership bitmap（已有营销侧，保持）
  Redis 不是账本。库存 available 禁止以 Redis 为权威。

L3 不可变快照
  - 营销已激活 ReleaseManifest / ArtifactBundle
  - 已投放 SkuTemplate 版本（不可变，修订出新 version）
  热路径只读快照，不回源 MySQL。
```

失效约定：

- 模板状态变更 → 删 L1 + 发布新 version，运行时按 version 读，不原地改已发券。
- 库存变更 **不** 写 L1 模板缓存。
- 券包入账成功 → 删该 subject 的 L2 摘要。
- 缓存 miss 必须可回源；回源失败 fail-closed，不返回过期「假装成功」。

建议模式：Cache-Aside + versioned key；保护用 single-flight / 请求合并，防缓存击穿；空值短 TTL 防穿透；热点模板永不过期但靠 version 切换。

### 0.5 DDD 聚合（禁止压成一张 Grant）

**benefit-center**

| 聚合 | 负责 | 不放进去 |
|---|---|---|
| `SkuTemplate` | 类型、面额、有效期规则、生命周期状态、等价 SKU 引用 | 人群、会场、互斥 |
| `InventoryAccount` | 配额 / 实物库存守恒 | 用户券实例 |
| `AwardOrder` | 一次 intent 的受理与 item 状态 | 券包展示模型 |
| `WalletEntry`（新） | 用户券实例或红包余额 | 渠道回执细节 |
| `Remediation` | 补发 / 冲正命令 | 对账勾兑算法 |

**marketing-lowcode-platform**

- `Campaign` / `DefinitionVersion` / `AudienceSnapshot` 保持原边界。
- `BenefitDefinition` 增加 `benefitSkuId` 引用，不复制模板字段。
- `AwardIntentOutbox`（新）是基础设施，不是领域核。
- Decision 继续只出报价 / OfferToken，不改券状态。

**recon-platform**

- ODS 与业务库隔离。
- Discrepancy / RemediationSuggestion 不执行资金，只出命令。

上下文之间只走版本化事件或同步命令 DTO，禁止共享 JPA entity。

### 0.6 必须使用的设计模式

| 模式 | 用在哪 | 不要做成 |
|---|---|---|
| Strategy | `ChannelAdapter`、MatchStrategy、限额策略 | 渠道 if-else 堆在 ApplicationService |
| Template Method / Pipeline | 受理 → 占库 → 出 operation → outbox | 复制粘贴每条渠道的事务边界 |
| Factory / Assembler | `AwardIntentAssembler`（营销 / drools） | Controller 拼 JSON |
| Outbox + Relay | 所有跨系统命令 | 业务事务里直接 HTTP 调中台 |
| Saga / 状态机 | AwardOrder、Reservation、Wallet | 分布式两阶段事务 |
| Circuit Breaker + Bulkhead | 渠道 HTTP、Redis、跨服务 check | 无限重试打满线程 |
| Repository | 聚合持久化 | Controller 直接 JDBC 拼 SQL |
| Specification | 领取资格、模板状态机迁移 | 散落的 boolean 工具方法 |
| Decorator / Filter | 租户、鉴权、幂等、审计 | 业务方法里手写重复 header 检查 |

代码可用性：领域层零 Spring / Kafka / Redis；适配器可替换；ArchUnit 继续挡依赖方向。

健壮性：同键同 payload 重放返回首次结果；同键不同 payload → 409；时钟用 `Clock`；测试固定 seed；失败码稳定可机器处理（`REPRICE_REQUIRED`、`UNKNOWN_MUST_QUERY`、`SKU_NOT_ACTIVE`）。

---

## Slice 1 — 权益中台：模板 + 券包（先做）

仓库：`benefit-center`

### 1.1 把 SKU 升级为模板（只扩资产形状）

现有 `SkuCommand`：`skuId, benefitType, faceValueMinor, currency, enabled`。

扩展（向后兼容，旧字段保留）：

```text
SkuTemplate
  skuId
  benefitType
  faceValueMinor?, currency?
  status: DRAFT | PENDING_APPROVAL | ACTIVE | PAUSED | RETIRED
  enabled                 # 由 status 派生：仅 ACTIVE 为 true；写接口可忽略客户端 enabled
  validityType: ABSOLUTE | RELATIVE
  validFrom?, validTo?    # 绝对窗口
  relativeDays?           # 领取后 N 天
  usableWeekdays?         # 可选
  dailyQuota?             # 履约侧日配额，不是活动玩法预算
  userLimitPerDay?
  userLimitTotal?
  equivalentSkuId?        # 产品化等价，缺货兜底仍要人工已审批的等价对
  version                 # 乐观锁 + 模板世代
```

**不要**把门槛、品类范围、互斥、会场、人群写进模板。那些在营销 Offer。

状态机：`DRAFT → PENDING_APPROVAL → ACTIVE ↔ PAUSED → RETIRED`。已发出的 `WalletEntry` 绑定 `skuVersion`，不随模板原地改有效期。

OpenAPI：扩展 `SkuCommand` / `SkuView`；列表过滤 `status`。

### 1.2 用户资产账户

发成功（item `SUCCEEDED`）同事务或 outbox 下一拍写入：

```text
WalletEntry
  tenant_id, entry_id, subject_ref
  sku_id, sku_version
  award_order_no, item_no
  asset_type: COUPON | CASH_BALANCE | CODE | PHYSICAL
  status: UNUSED | FROZEN | USED | EXPIRED | REVERSED
  expires_at
  face_value_minor?, currency?
```

红包 / CASH：可按 subject 聚合余额账本（只追加分录，重算余额）。

`AwardItem` 读模型增加可选 `walletEntryId`，入账成功后回填，供订单 Drawer 跳转券包。

查询（给前端 / 客服）：

```
GET /admin/v1/wallets/{subjectRef}
GET /admin/v1/wallets/{subjectRef}/entries?status=&skuId=&limit=
GET /openapi/v1/me/wallet          # 若暂无 C 端，可先只做 admin 客服查询
```

权限：Slice 1 只用现网 `benefit.admin`。不要先拆 `benefit.wallet.read`，否则 console 整站门禁进不去。

### 1.3 接单时用户级硬限额

现有只靠库存 CAS。补：

- 受理时用 L2 预检 + DB 唯一约束 / 计数表（`tenant+subject+sku+period`）CAS。
- 超限返回明确错误，不占库。
- UNKNOWN 的占额保持 reserved，直到查询收敛。

### 1.4 前端可接的读模型

工作台 overview 增加：`activeTemplateCount`、`walletIssued24h`（可后期）。  
Catalog 列表必须回传新字段，旧 console 忽略未知字段也不应 500。

### 1.5 测试

- 模板版本切换：旧券仍按旧 version 过期。
- 同 subject 超限 409 / 业务码。
- 履约成功 → wallet 可见；冲正 → REVERSED，余额回滚成对流水。
- 并发领取同一用户同一 SKU：不超过 `userLimitPerDay`。
- 缓存失效：ACTIVE→PAUSED 后新 intent 拒绝，L1 不得继续放行超过一代。

---

## Slice 2 — 营销：绑 SKU + 产出 AwardIntent

仓库：`marketing-lowcode-platform`（后端）+ 过渡期继续用 `drools-demo` 连接器。

### 2.1 BenefitDefinition 绑定

```
PUT /api/v1/benefits/{id}
  现有 name/status/resourceKey/policy
  + benefitSkuId: string | null
```

发布 / 提交审核门禁：`benefitSkuId` 必须指向 benefit-center **ACTIVE** 模板。用服务端调用或同步投影，**禁止前端替你保证**。

营销侧可做只读投影：

```
GET /api/v1/benefit-skus?status=ACTIVE
```

实现：BFF 调 benefit admin 或消费模板事件进只读表。带 L1 缓存，tenant 隔离。

### 2.2 AwardIntent 出站（对齐 drools 连接器）

不要信任控制台金额。流程：

1. 触发点（领取 / 支付成功事件 / 定向批次）带 `campaignId + definitionVersion + subject + sourceRequestId`。
2. `AwardIntentAssembler` 服务器端重算资格与金额。
3. 同事务写 `mk_award_intent_outbox`。
4. Relay 调 `POST /openapi/v1/award-orders`，`Idempotency-Key = sourceRequestId`。
5. 模式 `LEGACY | SHADOW | CENTER`，默认 LEGACY / SHADOW，按租户打开 CENTER。

同一 `sourceRequestId` 禁止营销与 drools 双发。切流用租户开关，SHADOW 对拍。

### 2.3 应发事实

成功入队或中台受理后发：

```
marketing.award-expected.v1
  tenantId, sourceRequestId, campaignId, definitionVersion,
  subjectHash, skuId, amountMinor?, quantity
```

recon 用它做「应发」源，**禁止**用履约事件冒充三方。

### 2.4 前端可接

- `BenefitView.benefitSkuId`
- `GET /api/v1/benefit-skus`
- 发放批次只读：`GET /api/v1/award-intents?campaignId=`（outbox 状态 PENDING/SENT/DEAD）

Journey 增加 `journey.award` 节点（Slice 2b，可后置）：config 只含 `benefitId` / 场景枚举，不含金额。

---

## Slice 3 — 对账打开权益 ODS

仓库：`recon-platform`

已有设计：`docs/benefit-center-integration.md`。本切片是**打开并补齐**，不是新概念。

1. Tenant-aware `RunKey` / `SourceReadContext`，禁止全租户扫。
2. 消费 `benefit.fulfillment-event.v1` 与 `marketing.award-expected.v1`。
3. 现金 `BENEFIT_CASH_3WAY`、非现金 `ENTITLEMENT_FULFILLMENT` 分模型。
4. Remediation 只生成建议；approve 后 outbox `benefit.remediation.command.v1`。
5. 开关默认仍可关；文档写清启用顺序与回滚（关 consumer）。
6. 前端已有差异 / 补救页：补「应发源缺失 / BRIDGE_BROKEN」展示字段即可，不要新造对账引擎 UI。

---

## Slice 4 — 审批 / 风控 / 核销

- workflow-platform：模板上线、超预算、补发冲正 BPMN。benefit / marketing 只发 `command.start.v1` 并消费回执改状态。
- risk-platform：在 Assembler 发出 intent 前 `check`；拒绝则不写 outbox。
- 交易核销：`POST /openapi/v1/wallet-entries/{id}:freeze|redeem|refund`，按原分摊，不按当前规则重算。

现金渠道、开放平台、商家自助单独立项，不塞进 Slice 1–3。

---

## 跨系统键（写进每个仓库 README 小节）

```
tenantId          = token owner，拒绝 header 覆盖
subjectRef        = 用户稳定引用（与 Casdoor sub 或营销 subject token 对齐需 crosswalk）
campaignId + definitionVersion
benefitSkuId + skuVersion
sourceRequestId   = 跨系统幂等钥匙
```

---

## Claude / Codex 工作方式

1. 一次只做一个 Slice，先契约与集成测试，再实现。
2. 不改 `frontend/`（营销）/ `benefit-console/` / `recon-console/`。
3. OpenAPI 变更后通知 Cursor：字段名、错误码、权限 scope。
4. 每个 PR 必须包含：ArchUnit 或模块边界证明、并发/幂等测试、缓存失效测试（Slice 1+）。
5. 生产结论需要压测报告；本地 Compose 数字不得写成 SLO。
