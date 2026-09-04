# 旧方案对抗性设计评审

## 评审对象与方式

- 对象：`docs/delivery/marketing-lowcode-foundation/DELIVERY_PLAN.md`。
- 目标：验证旧方案能否满足“京东式业务参考、低代码、活动营销、规则引擎、DDD、微服务、生产级、一期完整交付、不分期”。
- 方法：一次 Software Architect 独立评审、一次 Backend Architect 独立唱反调评审，再由主 Agent 对产品、领域、运行时、数据、合规和可交付性做综合裁决。
- 性质：设计评审，不是代码审查；目标目录尚无业务代码。

## Verdict

**旧方案拒绝批准，Production NO-GO。**

值得保留的原则：

- 控制面和执行面分离。
- 运营不能上传 Java、DRL、SpEL、Groovy 等任意代码。
- 类型化 DSL、不可变制品、四眼审批、outbox 和 at-least-once 消费。
- Drools 负责规则匹配/推理，资金计算使用可验证的纯 Java 领域算法。
- 每个服务拥有自己的数据，规则回滚不反向篡改已确认权益。

必须推翻的假设：

- “同步优惠是一期，旅程、触达、归因以后再做”。
- “预留接口等于交付完整能力”。
- “一个万能 RuleGraph 可以同时承载毫秒级算价和跨天旅程”。
- “Campaign 聚合持有所有大图和所有版本”。
- “`stackGroup + priority` 足以解决零售多层优惠”。
- “DecisionResult 后尽力 reserve 就是交易闭环”。
- “预算、折扣资金、券、积分、赠品和奖池都可以压成同一个 Grant”。
- “CAS 更新数据库指针 + 每个 Pod 本地切换等于集群一致发布”。
- “单行 MySQL 条件扣减可作为热点秒杀的最终生产方案”。
- “不定义数据规模、恢复目标和故障注入阈值，也可以承诺 p99/99.99%”。

## Confirmed Findings

| Severity | Finding | Concrete failure scenario | Old plan evidence | Resolution in replacement plan |
| --- | --- | --- | --- | --- |
| Blocker | 范围与用户要求直接冲突 | “下单未支付 30 分钟后发券并 Push”没有旅程、触达和归因能力 | 旧计划 7–12、119–126、242 行 | 同一 R1 纳入受众、事件、旅程、触达、实验和衡量 |
| Critical | 同步决策与异步旅程没有双运行时 | 把 Wait/Send 放入 RuleGraph 会让决策 Pod 持久化计时器；禁止又不完整 | 50–55、288–339 行 | `OFFER_DECISION_DAG` 与 `JOURNEY_STATE_MACHINE` 两种方言、两个运行时 |
| Critical | Campaign 模型层级过粗 | 一场大促有多个 Offer、旅程和实验，改一个短信模板会触发整包规则重新审批 | 79–89、267–271 行 | Campaign 只做协调根，组件独立版本，ReleaseBundle 固定依赖闭包 |
| Critical | 人群节点和“热路径无画像依赖”矛盾 | PLUS 规则拿不到标签则全拒绝；信任客户端标签又可伪造 | 39、111、131、225 行 | Audience Snapshot、字段来源/新鲜度契约、可信 inline context 和本地/Redis 索引 |
| Critical | 优惠组合语义不完整 | SKU 直降、店铺满减、跨店满减、平台券、运费券和赠品无法稳定择优 | 113、133–134、324–339 行 | 定价 stage、兼容矩阵、受限冲突图、确定性优化器与复杂度门禁 |
| Critical | 无优惠分摊与订单逆向 | 部分退款时无法退预算、返券、追回赠品或解释商家/平台出资 | 124、130–139、379–396 行 | 行级分摊、FundingShare、Application/Reservation、refund/reverse/clawback |
| Critical | 报价与权益没有可信绑定 | 客户端篡改金额或拿旧代际结果请求权益，订单价和权益状态分裂 | 332、354、358–361 行 | KMS 签名 OfferToken，绑定 tenant/subject/order/cart/generation/amount/expiry/nonce |
| Critical | Benefit 聚合过度压缩 | 券锁定、资金预算、赠品库存、积分和抽奖的状态机不同 | 283–286、379–396 行 | Coupon、Budget、Inventory、PrizePool、PromotionApplication 分离聚合和统一账本 |
| Critical | 发布协议只保证单点 CAS | Pod A 已 G43、Pod B 仍 G42，同一用户重试得到两个价格 | 280–281、335–336、393–395 行 | 签名 Manifest、区域复制、runtime ACK、cell 路由、stable/canary、多代保留和 reconcile |
| Critical | 热点库存锁队列 | 单个秒杀库存行让 MySQL 锁等待和连接池耗尽 | 44、396 行 | Bucket/Escrow quota、Redis 原子令牌、权威流水、fencing、异步持久化和对账 |
| Critical | 决策审计 API 没有数据所有者 | 客服按 requestId 查询时无数据库/索引，普通日志又不保存明细 | 238、355、365–386 行 | Measurement/Audit 拥有 ClickHouse 查询投影和对象存储原始证据 |
| Critical | 旅程合规闭环缺失 | 用户退订后仍被定时节点发送短信 | 119–126、242 行 | Consent/Suppression/Quiet Hours/Frequency 在发送前再次校验 |
| High | Studio 把多个上下文混为服务 | Rule、Campaign、Release 直接共享实体和事务，后续无法独立扩展 | 232–263 行 | 逻辑 Context Map + 平衡型物理部署，不一上下文一空壳服务 |
| High | 聚合热点与无限增长 | 一个 Release 根承载全租户活动集，发布互相阻塞 | 267–281 行 | 按 tenant/cell/runtime/namespace 分区 ReleaseSlot；版本/制品独立 |
| High | 低代码只有 JSON schema version，没有语义 ABI | plugin 改舍入语义后旧活动被当前代码重新解释 | 288–320、424–429 行 | Artifact 锁定 compiler/plugin/field schema/engine ABI digest；旧制品只加载不重译 |
| High | Drools 制品边界自相矛盾 | 控制面称已编译，决策面仍用不同补丁版本编译 DRL，激活失败 | 273–276、318–320、335–337 行 | 隔离 Compiler Worker 产出 engine-specific artifact，runtime 只加载兼容资产 |
| High | 事件乱序/缺口无法自愈 | inbox 先落、内存未切就宕机，重启把事件当已处理而永久缺版本 | 363、379–395 行 | desired-state Manifest 是权威，Kafka 只通知；gap detection + 周期 reconcile + 冷启动 |
| High | 幂等键粒度错误 | 同订单两个活动发同类券被错误合并；同 key 改 payload 未被识别 | 138、343–363 行 | commandId/intentId/applicationId + payloadHash + 原响应，冲突返回 409 |
| High | 多权益缺少原子边界 | 券成功、赠品失败、预算已占，订单却按完整优惠提交 | 283–286、358–361 行 | ReservationGroup 全成全败为默认；显式 partial policy + Saga compensation |
| High | 无归因与实验 | 平台不能回答增量转化、补贴 ROI 或 holdout 效果 | 107–125、232–242 行 | Exposure/Contact/Conversion/Refund 事实、实验层与可重算归因 |
| High | 合规设计不足 | 促销限制未公示、个性化营销无退出、平台强制商家出资 | 旧方案未建模 | TermsSnapshot、baseline price、merchant opt-in、generic alternative、PIPIA evidence |
| High | 无恢复目标和多地域写入策略 | 区域故障时库存双写超发或旅程状态丢失 | 407–414、525–534 行 | Cell 架构、控制单写、Decision active-active、Benefit home-region/escrow、Flink savepoint |
| High | 容量与测试不可证伪 | “100+ 金标”和 p99≤30ms 没有硬件/并发/时长/故障条件 | 151–160、477–491 行 | 明确容量公式、4h soak、3× burst、百万并发历史不变量、chaos/game day |
| Medium | 可观测性可能反向击穿 | 全量 trace/exporter 阻塞或 reason label 爆炸拖慢大促决策 | 418–422 行 | telemetry budget、tail sampling、异步有界队列、series allowlist、drop metrics |

## Options Considered

| Option | Topology | Advantages | Costs / failure modes | Decision |
| --- | --- | --- | --- | --- |
| A. 粗粒度 5–6 服务 | Control、Decision、Audience+Benefit、Journey+Engagement、Measurement、Gateway | 交付快、运维简单 | 资金、扫描计算、触达和旅程故障域互相污染 | 不满足生产隔离重点 |
| **B. 平衡型领域部署** | Control、Compiler、Audience、Decision、Benefit/Funding、Journey、Engagement、Measurement、Event Gateway，外加 Gateway/UI | 运行时和数据故障域清晰，能独立扩容，同时避免 15 个空壳 | 契约、部署和测试成本较高 | **采用** |
| C. 一上下文一服务 12–15 个 | Campaign、Offer、Rules、Release、Coupon、Quota、Consent 等全部独立 | 最大自治 | 绿地单次交付极易成为分布式 CRUD 与空壳服务 | 当前过度拆分 |

## Reconciled Decision

1. “一期全出”定义为**一个完整产品范围、一个最终发布、一个 DoD**；实现过程仍按依赖图施工，不对外形成半成品阶段。
2. 同一交付包含同步优惠和异步旅程，但以双 DSL、双运行时隔离。
3. 采用选项 B 的平衡型部署，monorepo 统一构建；服务独占数据，不共享领域/JPA 类型。
4. Control 维持统一运营体验与低流量治理事务；Compiler、Decision、Audience、Benefit、Journey、Engagement、Measurement 按故障域和负载独立。
5. Kafka 发布事件不是配置真值；签名 ReleaseManifest + 对象存储是 desired state，runtime 必须 ACK 并周期 reconcile。
6. Decision 只做纯报价；Benefit/Funding 接受签名 OfferToken，创建 PromotionApplication 和 ReservationGroup，负责所有有状态资金/库存效果。
7. Journey 使用通用 Flink keyed-state 运行时解释已编译 JourneyPlan；定义升级固定版本，外部效果始终幂等。
8. 合规不是文档尾注，而是字段、节点、发布门禁、运行时抑制和可验证验收项。

## Production Admission Result

替代方案可以进入代码交付，正式生产仍是 **conditional-go**。只有完整范围、发布一致性、资金不变量、容量、故障、灾备、安全、隐私、兼容、可观测和运维 game day 全部取得真实环境证据后，才能改为 production go-live。
