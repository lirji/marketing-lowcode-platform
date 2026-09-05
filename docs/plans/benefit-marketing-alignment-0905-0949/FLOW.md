# 联邦项目正常编排

日期：2026-09-05  
对照：同目录 `DECISION_RECORD.md`、`FINAL_PLAN.md`、`DEFERRED.md`；  
`docs/plans/slice4a-sku-golive-approval-0905-1530/`、`docs/plans/slice4bc-risk-and-redemption-0905-1630/`。

正常流程是**一条业务链、多台联邦**，不是合成一个控制台。人从门户进各台，系统用契约和事件串起来。

- 门户：http://localhost:5274  
- 身份只在 **auth-platform / Casdoor**（`:8000`）。各台自己登录，组织不要填错。

---

## 谁管什么

| 项目 | 入口 | 管什么 | 不管什么 |
|---|---|---|---|
| **auth-platform** | Casdoor `:8000`、门户 `:5274` | 登录、组织、角色、JWT | 活动、券、审批结论 |
| **权益发放中台** | `:8083` / API `:8183` | SKU 模板、库存、订单、券包冻/核/退 | 人群、Offer、旅程 |
| **流程审批** | `:8302` | 四眼待办（SKU 上线已接） | 改券状态、算金额 |
| **营销低代码** | `:8084` / 网关 `:18080` | 活动、Benefit 绑 SKU、Offer/Journey、发放只读 | 库存、核销表单、手工发奖 |
| **风控** | 控制台 `:15173`、网关 `:8082` | 发前拦截、规则、案件 | 写出 AwardIntent |
| **对账** | `:8088` | 应发 vs 已发差异、补救建议 | 自己执行补发/冲正 |
| **drools-demo** | `:8095` | 过渡生产者 | 新产品主路径（已否决） |

金额和 SKU 只在**服务端**算。控制台只配置、查询、外链。

权威源见 `DECISION_RECORD.md` §3。后置刀见 `DEFERRED.md`。

---

## 运营正常怎么走（人）

```text
① 权益 /catalog
     建模板（有效期/限额/类型）DRAFT
        ↓
② 权益「提交上线」→ 流程台待办
        ↓ 通过
   SKU = ACTIVE（营销才能选到）
        ↓
③ 营销 /designers/benefit
     绑这个 ACTIVE SKU（门槛/范围仍在营销）
        ↓
④ 营销画布：人群 / Offer / Journey
     发布活动（SKU 未 ACTIVE，后端拒）
        ↓
⑤ 用户侧触发（领取 / 支付后 / 定向）
     营销服务端组 AwardIntent → 先问风控
        ↓
   ALLOW → 权益接单、入账券包
   REJECT → 营销发放 tab 风控拦截，权益无单
        ↓
⑥ 客服权益 /wallets     交易回调冻/核/退
   运营营销 /operations?tab=awards  只看状态
        ↓
⑦ 对账看应发/已发；有差去流程/权益补救（后置）
```

切台用链接，不嵌页面：

| 从 | 到 |
|---|---|
| 营销发放 DEAD | 权益 `/orders?q={sourceRequestId}` |
| 权益订单已入账 | 权益 `/wallets?subject=&entry=` |
| 营销发放风控拦截 | 风控 `/decisions?q={sourceRequestId}` |
| 权益 SKU 待审批 | 流程待办 |

OIDC 本机组织：营销填 `marketing-platform`，权益填 `benefit-center`。两边租户不同，不要用 DEV 冒烟的 `retail-cn` / `dev-tenant` 去对 OIDC 数据。

---

## 系统正常怎么流（机器）

```text
营销 Assembler
  campaignId + definitionVersion + subject + sourceRequestId
  → 重算 SKU / 金额（不信前端）
  → POST 风控 /evaluations
       ALLOW  → outbox → POST 权益 /openapi/v1/award-orders
       拦截   → 只写 RISK_BLOCKED，不写 outbox
权益
  占库 → AwardOrder → WalletEntry
  → 事实事件（履约 / 核销）
营销
  → marketing.award-expected.v1（应发）
对账
  消费两边事件，只出差异和建议
审批
  业务同事务写 outbox command.start.v1
  → 禁止事务里 HTTP 调流程台
  → 回执再 CAS 改 SKU / 发布 / 补救状态
```

幂等钥匙全程是 `sourceRequestId`。同一把钥匙营销和 drools 不能双发。

---

## 现在能走完 vs 还要后置

| 环节 | 状态 |
|---|---|
| 权益建模板 + 4a 上线审批 | 已接 |
| 营销绑 ACTIVE SKU | 已接 |
| 发前风控 + 发放 tab 只读 | 已接（OIDC 租户下要重新产生 intent） |
| 客服券包冻/核/退 | 已接 |
| Journey 节点真正出 AwardIntent | **2b 后置**（画布现在是空的 `grant`） |
| 对账应发源、超预算/补发审批 | 后置 |
| C 端「我的券包」 | 后置 |

今天「正常验」到：权益 ACTIVE SKU → 营销绑上 → 服务端发奖（或看发放只读）→ 风控过了才有权益单 → 券包核销。  
不要在 Journey 上拖「发放权益」就当已发奖。细节见 `DEFERRED.md`。

---

## 本机启动也按这条链

内存不够就停后面的，不要一次 `up` 全栈。

```text
1. 底座：MySQL / Redis / Kafka + Casdoor :8000 + 门户 :5274
2. 权益 API :8183 + 控制台 :8083     （先有 ACTIVE SKU）
3. 流程 :8300 / :8302                 （要验 4a 审批再开）
4. 营销网关 :18080 + 控制台 :8084
   至少：control + funding + gateway + console
5. 风控 gateway :8082（+ Redis）；控制台 :15173 可选
6. 对账 :8088                         有差异再开
7. Flink / ClickHouse / Keycloak      默认别开
```

`--secure` 是身份叠层，不改变上面的业务顺序。  
营销 OIDC 下 `benefit-funding-service` 必须配置 `RISK_PLATFORM_BEARER_TOKEN` 才能起来。

---

## 一句话

先在权益把「发什么」上线，再在营销编排「发给谁、何时发」，风控只挡这一下，履约和券包只在权益，人对账和审批各自一台上办。门户只负责跳转，不负责业务。
