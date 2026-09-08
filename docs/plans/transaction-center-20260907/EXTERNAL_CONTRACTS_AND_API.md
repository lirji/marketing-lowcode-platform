# 交易中心外部合同及 API 清单

本文“合同”指可签署的服务间技术契约，不是已签法律协议。以下交易中心接口均为拟新增；现有平台能力按总纲区分。实施必须生成 OpenAPI 3.1、AsyncAPI、JSON Schema、消费者驱动合同测试及错误码 SDK，本文不是声称这些机器合同已存在。

## 1. 公共协议

- HTTPS，UTF-8 JSON；时间 RFC3339 UTC；ID 为字符串；金额为最小币种单位整数，范围不超过 JavaScript 安全整数及渠道上限中的较小值，后端拒绝越界；币种显式传递。
- 外部 `/api/v1/trade` 由网关／BFF 接入；登录凭据不能被客户端 tenantId/memberId 覆盖。管理员另有操作权限；内部 API 服务身份＋audience＋租户授权三项校验。
- 写请求带 `Idempotency-Key`，作用域为租户＋主体＋操作＋键；相同键不同规范化请求体返回 409 `IDEMPOTENCY_CONFLICT`；已完成返回同资源，处理中返回 202 和查询地址。请求号保留范围见数据库规范。
- 同步创建成功 201；查询 200；异步受理 202；参数 400；未认证 401；无权 403（对象存在性敏感时统一404）；冲突409；业务条件不满足422；限流429带 Retry-After；依赖不可用503。渠道回调使用渠道专用应答格式，不强套统一包装。
- 错误体包含 `code,message,traceId,retryable`。状态未知不是支付失败；`retryable=true` 也必须沿用原幂等键。分页使用游标，默认20最大100；禁止无界列表和任意排序字段。
- 管理更新带 `If-Match` 版本，过期返回412；列表与详情返回版本。traceparent 跨服务透传，敏感字段不进日志。

```json
{
  "code": "PAYMENT_RESOLUTION_PENDING",
  "message": "原支付结果尚未确认，请查询原支付单",
  "traceId": "tr_demo_001",
  "retryable": false
}
```

## 2. 外部 API 一览

表中 M=当前会员，A=后台授权操作员，S=内部授权服务，C=已验签渠道。所有写接口都有参数校验和审计；GET 不产生支付／退款副作用。

| 方法与路径 | 身份 | 核心请求 → 响应／约束 |
|---|---|---|
| POST `/api/v1/trade/members:register` | 登录主体 | consentVersions → memberId,registeredAt；身份来自令牌，重复注册返回原会员 |
| GET `/api/v1/trade/members/me` | M | → 脱敏资料、业务状态 |
| PATCH `/api/v1/trade/members/me` | M | 允许更新的资料＋版本 → 新版本；改手机号须另行验证，不直接改登录绑定 |
| POST `/api/v1/trade/members/me/consents` | M | purpose,documentVersion,action → 同意记录 |
| GET `/api/v1/trade/admin/members` | A | 游标、精确过滤 → 脱敏会员列表 |
| POST `/api/v1/trade/admin/members/{id}:change-status` | A | targetStatus,reason → 状态；冻结不阻止合法退款入账 |
| POST `/api/v1/trade/admin/products` | A | 名称、SKU 草稿 → productId |
| PATCH `/api/v1/trade/admin/products/{id}` | A | 草稿变更＋版本 → 新草稿版本 |
| POST `/api/v1/trade/admin/products/{id}:publish` | A | expectedVersion → 发布快照版本；发布后快照不可改 |
| POST `/api/v1/trade/admin/products/{id}:unpublish` | A | reason → 下架；历史订单不受影响 |
| GET `/api/v1/trade/products` | M | 游标、过滤 → 已上架商品摘要 |
| GET `/api/v1/trade/products/{id}` | M | → SKU、版本、展示价格；库存仅供展示 |
| POST `/api/v1/trade/quotes` | M | items[skuId,quantity] → quoteNo,expiresAt,items,amount,currency |
| POST `/api/v1/trade/admin/warehouses` | A | warehouseNo,name → warehouseId |
| GET `/api/v1/trade/admin/inventories` | A | warehouseId,skuId,游标 → 可售／预占／已售／总量 |
| POST `/api/v1/trade/admin/inventory-adjustments` | A | skuId,warehouseId,delta,reason,externalRef → operationNo；不能扣成负数 |
| GET `/api/v1/trade/admin/inventory-ledgers` | A | SKU、仓库、游标 → 库存审计流水 |
| POST `/api/v1/trade/orders` | M | quoteNo,address（实物必需）,referralToken可选 → orderNo,status,amount,expiresAt |
| GET `/api/v1/trade/orders` | M | 游标 → 本人订单 |
| GET `/api/v1/trade/orders/{orderNo}` | M | → 订单快照、履约／财务状态；只读 |
| POST `/api/v1/trade/orders/{orderNo}:cancel` | M | reason → 202 CLOSING 或已取消；不能跳过渠道终态确认 |
| POST `/api/v1/trade/orders/{orderNo}/payments` | M | channelCode,returnUrlId → paymentNo,status,paymentAction；返回地址必须预登记 |
| GET `/api/v1/trade/payments/{paymentNo}` | M | → 本地支付状态、下次查询建议；用户回跳不能作为成功证据 |
| POST `/api/v1/trade/orders/{orderNo}/refunds` | M/A | amount,items,reason → refundNo,status；权限区分用户申请和后台批准 |
| GET `/api/v1/trade/refunds/{refundNo}` | M/A | → 退款状态、金额、失败／未知原因 |
| POST `/api/v1/trade/admin/orders/{orderNo}/fulfillments` | A | deliveryRef,sourceRequestId → 履约单号 |
| POST `/api/v1/trade/orders/{orderNo}:confirm-receipt` | M | → 完成状态；服务类由可信交付服务完成 |
| POST `/api/v1/trade/admin/returns` | A | orderItemId,warehouseId,quantity → returnNo |
| POST `/api/v1/trade/admin/returns/{returnNo}:inspect` | A | result,restockQuantity,evidenceRef → 验收与入库操作号 |
| POST `/api/v1/trade/admin/refunds/{refundNo}:approve` | A | reason,approvalRef → 批准状态；受流程审批约束，不能自审自批 |
| POST `/api/v1/trade/admin/refunds/{refundNo}:reject` | A | reason → 拒绝终态；未发往渠道才可拒绝 |
| GET `/api/v1/trade/admin/payments` | A | 状态、时间窗、游标 → 支付运营查询 |
| GET `/api/v1/trade/admin/refunds` | A | 状态、时间窗、游标 → 退款运营查询 |
| GET `/api/v1/trade/admin/reconciliation-cases` | A | 状态、游标 → 资金差异列表 |
| POST `/api/v1/trade/admin/reconciliation-cases/{caseNo}:resolve` | A | action,reason,approvalRef → 受控修复任务；禁止任意 SQL |
| POST `/callbacks/v1/payments/{channelCode}` | C | 原始签名报文 → 渠道确认格式；成功应答表示已持久接收，不一定已完成业务处理 |
| POST `/callbacks/v1/refunds/{channelCode}` | C | 原始签名报文 → 同上 |

库存预占／释放首期为模块内部 Java 端口，不能为了接口数量公开给前端；否则前端可绕过订单制造库存预占。

## 3. 内部事实合同：会员与营销

拟新增、与裂变方案对齐：

| 方法与路径 | 输入 | 输出与保证 |
|---|---|---|
| POST `/internal/v1/customer-eligibility:query` | memberId,policyVersion,campaignStartAt,evaluatedAt | registeredAt,everPaid,firstPaidOrderNo,factVersion,eligible,reasonCodes,evaluatedAt；读取主库一致快照，不能当资格预占 |
| GET `/internal/v1/orders/{orderId}/referral-evidence` | 租户范围内订单 ID | 会员、首单规则、支付／完成／退款事实、净支付、证据版本 |
| POST `/internal/v1/order-referral-evidence:query` | orderIds（最多100） | 每单独立 FOUND/NOT_FOUND 与证据，不返回其他租户存在性 |
| POST `/internal/v1/orders/{orderId}:complete` | sourceRequestId,deliveryEvidenceRef | 服务交付完成事实；仅可信履约服务可调用 |
| POST `/internal/v1/refunds/{refundNo}/approval-results` | approvalRef,decision,decisionVersion | 审批事件幂等回执；不能覆盖已执行退款 |

```json
{
  "orderId": "90010001",
  "memberId": "80010001",
  "evidenceVersion": 7,
  "firstOrderPolicy": "FIRST_CONFIRMED_PAID_ORDER_V1",
  "isFirstConfirmedPaidOrder": true,
  "paidAmount": 10000,
  "refundedAmount": 2000,
  "pendingRefundAmount": 1000,
  "netPaidAmount": 8000,
  "conservativeEligibleAmount": 7000,
  "currency": "CNY",
  "paidAt": "2026-09-07T04:00:00.000Z",
  "completedAt": "2026-09-07T05:00:00.000Z",
  "asOf": "2026-09-07T06:00:00.000Z"
}
```

netPaidAmount=正常订单实收减成功退款；pendingRefundAmount 不伪装为成功退款，但奖励保守评估可使用扣除在途退款后的金额。多付补偿不增加订单消费额。营销保存采用的证据版本和规则版本；更新事件乱序时按版本查询最新证据，而非覆盖成旧金额。

## 4. 支付渠道必须签署并验证的合同

| 端口 | 必须明确的行为 | 验收证据 |
|---|---|---|
| `createPayment` | merchantRequestNo 幂等范围／有效期，同号不同金额拒绝，创建超时如何查询 | 沙箱同号重试、请求到达后断网测试 |
| `queryPayment` | 查询依据、SUCCESS/CLOSED/FAILED/PENDING/UNKNOWN 映射、金额币种、交易号 | 不存在与未传播的差别、最终终态文档 |
| `closePayment` | 成功应答是否构成不可再扣款屏障，支付与关单并发谁胜出 | 渠道正式语义＋竞争测试；仅 HTTP 成功不合格 |
| `verifyCallback` | 原始 body 验签、证书轮换、商户绑定、防重放、应答和重试周期 | 错签、错商户、重复、乱序、轮换测试 |
| `createRefund` | 原路退款、商户退款号幂等、部分退款次数／额度、超时查询 | 并发部分退款和断网重试测试 |
| `queryRefund` | UNKNOWN 与永久失败区别、最终金额和交易关联 | 延迟成功、重复成功、超额拒绝 |
| `downloadStatement` | 账单时区、结算日、手续费、金额正负、补发／更正账单、完整性 | 支付退款手续费映射样例与校验和 |

外部调用建议初始连接超时500ms、单次响应预算3s，最终按渠道 SLA 调整。查询采用退避＋抖动（如5s、15s、60s至5min封顶），设租户与渠道配额；超过业务时限进入人工队列，但事实不能丢弃或误判失败。定时查单覆盖无回调情形。

渠道、商户归属、费率、账期、回调证书、证书轮换、限额、退款期限、SLA 和事故联系人均待选型签署。未取得真实渠道材料时只能完成模拟适配／沙箱，不得称“生产支付接通”。

## 5. 事件合同

消息基础设施优先复用 dev_infra 的 `kafka38`（`apache/kafka:3.8.0`），不为交易中心新建 Kafka 或并行引入 RabbitMQ。容器 bootstrap 为 `infra-kafka38:9092`，本机为 `localhost:49092`。下列 topic 名为逻辑合同；多环境共用 broker 时统一加环境前缀，并在所有生产者／消费者配置中映射。交易、营销、对账使用各自消费组，不能共组导致事件被彼此分摊。接入须明确 topic ACL、分区键（租户＋聚合ID）、保留周期、配额及消费者重放范围；这些均为待配置项，不能因容器存在就认定已完成。生产 broker 数量、复制因子、min.insync.replicas 与 acks 设置需满足并验证持久性目标，不能照搬开发单节点配置。

建议 topic：`trade.member-registered.v1`、`trade.order-paid.v1`、`trade.order-completed.v1`、`trade.order-cancelled.v1`、`trade.refund-succeeded.v1`、`trade.inventory-changed.v1`。会员、订单、库存使用各自聚合键；不能假设跨 topic 全局顺序。

```json
{
  "eventId": "evt_demo_001",
  "eventType": "trade.refund-succeeded.v1",
  "schemaVersion": 1,
  "tenantId": "1001",
  "aggregateType": "ORDER",
  "aggregateId": "90010001",
  "aggregateVersion": 7,
  "occurredAt": "2026-09-07T06:00:00.000Z",
  "producer": "transaction-center",
  "traceId": "tr_demo_001",
  "data": {
    "memberId": "80010001",
    "orderId": "90010001",
    "refundNo": "rf_demo_001",
    "refundAmount": 2000,
    "cumulativeRefundedAmount": 2000,
    "netPaidAmount": 8000,
    "currency": "CNY",
    "evidenceVersion": 7
  }
}
```

各事件 data 必填：

- member-registered：memberId,registeredAt,registrationSource。
- order-paid：orderId,memberId,receiptNo或内部receiptId,paidAmount,currency,paidAt,firstOrderPolicy,isFirstConfirmedPaidOrder,evidenceVersion。
- order-completed：orderId,memberId,completedAt,evidenceVersion。
- order-cancelled：orderId,memberId,cancelledAt,reason,evidenceVersion。
- refund-succeeded：示例字段，禁止只发本次退款金额让消费者猜累计状态。
- inventory-changed：warehouseId,skuId,operationNo,available,reserved,sold,balanceVersion；不携带会员个人信息。

事务提交后发布；发布成功但状态未写回会重发。消费者事务内用 eventId 去重，重放不得二次奖励／入账。普通增量事件保留兼容新增字段；删除／改类型／改含义升主版本，双发迁移需跨版本业务去重键。每种事件提供最小／完整／错误 JSON 固件。死信有告警、工单、修复与受控重放；不能无限静默重试。

## 6. 跨系统责任与交付物

| 提供方 → 消费方 | 责任 | 上线合同门禁 |
|---|---|---|
| Auth → Trade | issuer/sub 验证、服务令牌、操作权限 | 租户与 audience 越权用例；身份映射稳定 |
| Trade → Marketing | 注册／首付／完成／退款事实，证据查询 | 不重奖、不把退款老客当新客；归因仍由营销决定 |
| Marketing → Trade | 可选报价优惠／归因令牌 | 签名、租户、会员、版本、过期、单次使用语义；未接通前禁用相关入口 |
| Risk → Trade | 支付风险结果 | 交易请求映射、延迟预算、拒绝／超时策略；注册风险另补合同 |
| Workflow → Trade | 退款审批结果 | 审批人权限、不可自批、结果版本和幂等 |
| Trade／渠道账单 → Recon | 支付退款资金事实与证据 | 长款短款、金额差异、手续费、跨日、更正账单覆盖 |
| Recon → Trade | 受控修复命令 | 双人审批或同等控制、业务幂等、全链路审计，不直写交易库 |

每份合同验收必须记录提供方负责人、消费方负责人、版本／Schema SHA、样例、错误码、超时／限流、数据保留、兼容窗口、证书配置来源、沙箱报告和事故升级联系人；这些当前均为待填项，不伪造负责人签字。
