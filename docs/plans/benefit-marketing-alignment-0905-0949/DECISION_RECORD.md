# 决策记录：权益 / 营销 / 对账对齐

日期：2026-09-05  
范围：跨仓库产品对齐，不是单页改文案。  
执行分工：Cursor 只做各控制台前端；Claude Code / Codex 做全部后端。

对照：`/Users/liruijun/Desktop/权益发放中台与京东淘天能力差距.md`

## 1. 问题

现有履约核（幂等 `AwardIntent`、占库、UNKNOWN、补发/冲正）已对齐头部公司发奖内核。缺的是产品层：模板、券包、领取投放、核销回写、跨系统对账协同。

用户追加约束：

- 服务端按互联网三高设计：高并发、高可用、高性能。
- 代码可用、健壮；充分使用设计模式、DDD、多层缓存。
- 页面能低代码就走营销低代码平台，不另起一套 CRUD 中台。

## 2. 备选方案

| 方案 | 做法 | 优点 | 代价 | 裁决 |
|---|---|---|---|---|
| A 联邦控制台 | 三 SPA 独立，只对齐契约与 deep link | 改动小、发布独立 | 运营要切多个台 | **Slice 1–2 采用**（运营页） |
| B 设计态进营销画布 | SKU/领取/发放编排做成营销 dialect | 符合低代码方向 | 需后端接受营销定义为权威源之一 | **设计态采用轻量 B** |
| C 合并运营壳 | benefit + recon 合成一个 Ant 壳 | 少切台 | 不解决模板/券包；营销栈不合 | **本阶段不做** |
| D 以 drools-demo 为主路径 | 继续堆活动引擎 | 已有 AwardIntent 连接器 | 与营销平台双主链路 | **否决**。规则引擎只作过渡生产者 |

## 3. 推荐架构（已裁决）

```text
设计 / 资格 / 领取玩法     → marketing-lowcode-platform（低代码）
权益产品 / 用户资产 / 履约 → benefit-center
三方账平 / 纠错建议       → recon-platform
人工四眼                   → workflow-platform（Slice 4）
发前拦截                   → risk-platform（Slice 4）
过渡期 AwardIntent         → drools-demo 连接器，直到营销能出同等 intent
身份                       → auth-platform（不改业务规则）
```

权威源：

| 对象 | 权威 | 其它系统只引用 |
|---|---|---|
| 活动、人群、Offer、旅程、领取场景 | 营销 `Campaign` / `DefinitionVersion` | benefit 只记 `campaignId` + version |
| 权益资产形状（有效期、类型、配额账户） | benefit `SkuTemplate` | 营销 BenefitDefinition 绑 `benefitSkuId` |
| 用户券包 / 红包余额 | benefit 用户资产账本 | 营销、客服只读查询 |
| 发放执行 / 渠道 UNKNOWN | benefit AwardOrder | 营销不重做状态机 |
| 应发 / 已发 / 渠道勾兑 | recon ODS | 营销与 benefit 只出事实事件 |
| AwardIntent 金额 / SKU | 服务器端重算，禁止前端提交 | 控制台禁止手工发奖 |

## 4. 低代码边界

进营销低代码（复用 `LowCodeDesigner` / Audience / Benefit 编辑器）：

- 活动绑定已投放 SKU
- 领取 / 支付后 / 定向 三种发放场景（Journey 节点或 Benefit 表单扩展）
- Offer 门槛、范围、互斥（已有画布，不迁到权益中台）

保持 Ant CRUD（benefit / recon 运营台）：

- SKU 模板、路由、库存调整
- 发放订单点查、应急补发
- 对账运行、差异、处置建议

不画布化：对账场景 JSON、库存加减、客服点查。

## 5. 视觉与端形态

- 不统一三套 UI 框架。营销继续自研 CSS（`styles.css` teal `#087f75`）；benefit 继续 Ant `#0F6F6A`；recon 继续 Ant `#315EFB`。
- 三端都是桌面内部运营台。窄屏只要求「能查、能看状态」，不要求设计器可编辑。
- 营销沿用 720px 抽屉导航；benefit/recon 沿用 768 / 992。不另起断点体系。

## 6. 后端三高（给 Claude / Codex 的硬约束）

不得做成「加字段的 CRUD 服务」。每一刀后端必须满足：

1. **高并发**：租户隔离（tenant 前缀主键、bulkhead、连接池配额）；热点 SKU 库存分桶；写路径无全表扫描；命令带 `Idempotency-Key`。
2. **高可用**：受理与 outbox 同事务；渠道 I/O 在事务外；UNKNOWN 只查询原 operation；多副本 lease + CAS；无单点内存真值。
3. **高性能**：决策 / 领券读路径禁止同步打控制库或跨上下文 JOIN；多层缓存（见 BACKEND_HANDOFF）。
4. **DDD**：模板、资产账户、AwardOrder、券实例分聚合；营销 Decision 继续只报价。
5. **设计模式**：见 BACKEND_HANDOFF §4，禁止在 Controller 里堆业务 if-else。

## 7. 明确不改 / 不臆造

- 不合并三个前端仓库，不抽 `@platform/console-ui`（本阶段假设）。
- 不在 benefit-center 做会场、人群、互斥画布。
- 不在营销里做渠道发券状态机或三方勾兑。
- Slice 1 不做积分、E 卡、膨胀红包、开放平台 ISV、真实现金渠道。
- 不在前端硬编码券包 / 发放数据。`DEMO_MODE` 仅营销演示开关，新页 live 必须走接口。
- 后置刀的做法与开刀顺序见同目录 `DEFERRED.md`，本阶段不实施。
- 联邦项目正常编排见同目录 `FLOW.md`。

## 8. 待用户确认的假设

1. 产品主路径以营销低代码为准，drools 只过渡。
2. 券包与模板权威在权益中台。
3. 核销由交易回调权益中台，营销只收事实事件（Slice 3 以后）。
4. 桌面为主，移动端降级查看。
5. Slice 1 先打通「模板 + 券包查询 + 营销绑 SKU」，不先做领取玩法全套。
6. 绑 SKU 后，有效期/面额以模板为准；营销 BenefitEditor 只继续编门槛、范围、出资、退款。
7. Slice 1 券包与 Catalog 权限保持 `benefit.admin`。
8. Slice 2 发放只在运营页按 `campaignId` 查询，不含 Journey 节点。
