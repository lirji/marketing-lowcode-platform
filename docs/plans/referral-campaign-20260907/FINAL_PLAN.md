# 裂变活动实施方案

日期：2026-09-07。状态：方案交付，尚未实施。依据：当前仓库代码、API/事件契约与已有权益中心对接方案；未验证外部会员、订单、风控、权益中心仓库和生产环境。

生产详细设计已补充为v2。本文保留基础范围与逐文件实施清单；生产事务、数据模型、API合同和验收以以下材料为准：

| 文档 | 内容 |
|---|---|
| [PRODUCTION_DESIGN.md](PRODUCTION_DESIGN.md) | 容量包络、SLO、多AZ、设计模式、完整流程、分桶配额、授权/取消竞态与恢复 |
| [DATABASE_DESIGN.md](DATABASE_DESIGN.md) | 完整字段字典、主键/索引、注释强制要求、带注释DDL示例、迁移验收 |
| [EXTERNAL_CONTRACTS.md](EXTERNAL_CONTRACTS.md) | BFF/会员/订单/风控/权益中心目标合同、字段、错误码、幂等与签收要求 |
| [ACCEPTANCE.md](ACCEPTANCE.md) | 28项功能/并发断言、容量测试、故障演练、对账与注释门禁 |
| [PROJECT_HIGHLIGHTS_AND_API.md](PROJECT_HIGHLIGHTS_AND_API.md) | 项目重点、现有修改API、新增内部/运营API和外部接口汇总 |

v2仍为设计交付，不是已投产认证；所有新增表及字段必须中文COMMENT，所有新增类/公共API及关键代码路径必须有明确中文注释，并通过自动检查与人工评审。

## 1. 交付目标与范围

首期新增原生「邀请有礼」能力：运营创建活动、配置邀请条件及奖励、审批发布；用户生成邀请链接；好友登录后绑定邀请关系；注册或首单达标后，分别计算邀请人和被邀请人的奖励；支持邀请人数阶梯、退款失效、发奖结果查询与对账。

这是本方案的建议范围，不代表业务方已确认所有参数。首期包含两种资格模式：REGISTERED_NEW_CUSTOMER（新客注册）和 FIRST_ORDER_SETTLED（首笔有效订单结算），一个活动版本选择一种。首期奖励选已接入权益中心的固定优惠券 SKU，暂不开放现金、随机红包、实物地址采集。双边奖励独立履约，不承诺两人同时成功。

后续独立迭代：好友助力、拼团、砍价、排行榜、多层邀请奖励。这些玩法分别需要助力任务、团实例、价格状态机等模型，不应因为新增邀请关系就标记为已支持。首期只奖励直接邀请关系，不递归给上级发奖。

建议首个试点：老用户 A 邀请新用户 B；B 在绑定后 7 天内完成首笔满足门槛的订单并经过退款观察期；B 获得一张新客券；A 每有效邀请 1 人获得一张券，累计 3 人、5 人各追加一档奖励。具体券、门槛、期限和数量由运营配置并审批，不写死在页面或业务代码。

### 建议业务默认值

| 项目 | 首期建议 | 需要明确的影响 |
|---|---|---|
| 归因 | 同一租户、活动内，被邀请人首次有效绑定获胜 | 不允许换邀请人；新版本不重置归因 |
| 新客 | 活动绑定时会员系统认定的新客 | 手机号/账号相同与用户合并必须有统一主体映射 |
| 首单 | 订单系统给出的权威首笔有效订单标识 | 本平台收到的第一条订单消息不能代表首单 |
| 时间 | 绑定窗口为活动开始到结束；达标截止为 min(绑定+配置天数, 活动结算截止) | 结算截止可晚于报名结束；时间区间明确采用 [开始, 结束) |
| 有效金额 | 订单结算可计入金额减已确认退款；币种相同 | 满减、运费、税费是否计入由订单适配合同定义 |
| 阶梯 | 达到 3、5 人分别追加一次；与逐人奖累计 | 禁止活动中原地改为互斥阶梯 |
| 退款 | 达标人数可减；未发奖取消；已发奖进入追回流程 | 观察期能降低但不能消除退款风险 |
| 二次达标 | 同一关系、同一阶梯里程碑不重复奖励 | 失效后人数再次到达相同门槛，不再生成新奖励身份 |
| 风控不可用 | 保留待评估，暂停资格或发奖，恢复后重试 | 不把服务不可用当成通过 |
| 发布切换 | 一个活动一条 referral 发布线；新版本只作用于新加入的邀请人 | 旧参与者和已绑定关系固定旧版本 |

实施开始前应冻结新客/首单口径、退款政策、券 SKU、有效期和规模目标；这些选择不妨碍先完成本文的接口、数据结构和开发任务拆解。

## 2. 现状核验与不足

以下是已核实的现有文件，不把历史设计文档中的愿景当成已实现功能。

| 证据（仓库相对路径） | 已有能力 | 本次必须补充 |
|---|---|---|
| `services/marketing-control-service/src/main/java/com/acme/marketing/control/domain/Campaign.java:7` | 活动身份、状态、名称、目标 | 活动类型与裂变定义绑定 |
| `runtime-spi/lowcode-language-core/src/main/java/com/acme/marketing/lowcode/model/Dialect.java:3` | 五种定义方言 | 第六种 REFERRAL_POLICY 与完整编译/发布支持 |
| `services/marketing-control-service/src/main/java/com/acme/marketing/control/application/DefaultNodeRegistry.java:37` | 通用 Journey 节点 | 邀请规则节点、专用校验与仿真 |
| `runtime-spi/journey-runtime-spi/src/main/java/com/acme/marketing/journey/EnrollmentSnapshot.java:7` | 一个流程实例对应一个主体 | 跨用户关系、去重计数、分角色奖励 |
| `runtime-spi/journey-runtime-spi/src/main/java/com/acme/marketing/journey/JourneyRuntime.java:25` | 合并事件属性、条件判断和副作用命令 | 没有新客、有效邀请人数和双边资格的领域判断 |
| `services/benefit-funding-service/src/main/java/com/acme/marketing/benefit/application/BenefitFundingService.java:231` | Journey 库存/奖品直接发放 | 不适合作为本次权益中心双边奖励入口 |
| `services/benefit-funding-service/src/main/java/com/acme/marketing/benefit/application/AwardIntentAssembler.java:45` | 从签名 OfferToken 重建发奖内容、校验主体 | 邀请达标没有购物车 OfferToken，需要新增可信授权来源 |
| `services/benefit-funding-service/src/main/java/com/acme/marketing/benefit/application/AwardIntentService.java:81` | 风控、租约、去重、outbox | SOURCE_SYSTEM 多处固定为 drools-activity，不能只换 DTO 就复用 |
| `services/benefit-funding-service/src/main/java/com/acme/marketing/benefit/infrastructure/AwardIntentRelay.java:125` | POST 权益中心，拿 awardOrderNo，标记 SENT | HTTP 202/SENT 仅表示受理；缺少本次所需的最终履约、退款追回闭环 |
| `services/benefit-funding-service/src/main/java/com/acme/marketing/benefit/application/RiskEvaluationGateway.java:18` | 主体、交易号、金额、币种评估 | 邀请人、被邀请人、可信设备关联、关系风险场景 |
| `services/event-gateway-service/src/main/java/com/acme/marketing/eventgateway/application/EventPayloadRouter.java:31` | PROFILE_CHANGED/JOURNEY_SIGNAL/MARKETING_FACT 特殊路由 | 邀请、会员、订单和退款事实的定向路由与来源授权 |
| `services/marketing-control-service/src/main/java/com/acme/marketing/control/application/ReleaseApplicationService.java:355` | decision/journey 制品边界与 ACK | referral 运行时、SKU 门禁、版本冻结和 kill switch |
| `frontend/apps/console/src/features/release/ReleaseWizard.tsx:28` | 仅 decision/journey 发布选择 | referral 编译格式、就绪检查、发布入口 |
| `platform-web/src/main/java/com/acme/marketing/platform/web/TenantContextFilter.java:56` | 管理端/机器 JWT 的租户和权限上下文 | C 端可信用户身份接入；不能给浏览器发机器凭证 |

纠正两种容易导致误实施的判断：自定义事件可以携带邀请属性，但不等于已有裂变归因；Journey 能扣库存并写发放记录，也不等于已通过权益中心完成双边券奖励。

## 3. 架构选择

选择新增 `services/referral-service`，MySQL 保存关系、资格、奖励资格账本，Kafka + outbox 传递事实。复用当前 control、compiler、event gateway、benefit、engagement、measurement。首期不新增 Flink Job，按活动+邀请人分区处理，并用数据库事务覆盖跨分区竞争。

| 方案 | 决定 | 理由 |
|---|---|---|
| 全部塞入 Journey 变量/节点 | 不选 | 跨人关系、并发去重、退款和奖励唯一性不能靠单实例变量保证 |
| 直接让浏览器提交 AwardIntent | 不选 | 客户端不能声明达标、受益人或发奖金额 |
| 把裂变运行态放入 control | 不选 | 高频用户事件会与审批发布事务耦合 |
| 新建 referral-service，复用治理与发奖基础设施 | 采用 | 明确资格权威来源；服务数量增加一个，职责可测试 |

所有普通用户流量由现有商城/H5 的可信 BFF 接入。BFF 的身份协议是外部依赖；本仓库先交付只允许 BFF 调用的参与接口。首期页面由 Cursor 在实际 C 端项目实现，未提供项目位置前不假定已经存在。

端到端顺序：

1. 控制面保存 REFERRAL_POLICY → 校验/仿真 → 审批 → compiler 输出 REFERRAL_PLAN → referral 预热、签名 ACK → 激活。
2. A 登录，在活动中加入，固定规则版本，生成高熵邀请 token。
3. B 打开链接，BFF 查询活动简介；B 登录并确认参加后，服务端验证 token，创建 A→B 关系。
4. 会员/订单系统发送可信事实；referral 校验新客/首单、归因窗口、观察期、风控，改变资格状态。
5. 同一事务更新有效邀请人数、生成逐人/阶梯/被邀请人奖励资格及 outbox。
6. referral 生成服务端签名奖励凭证 → benefit 验证资格、规则和 SKU，执行发前风控 → AwardIntent → 权益中心。
7. 权益中心最终结果 → benefit 履约投影/outbox → referral 奖励状态；独立向 measurement 和可选 Journey 发送事实。
8. 退款/资格撤销 → 重算有效资格 → 取消待发或发起追回 → 对账直至终态或人工待办。

## 4. 新增数据结构

### 4.1 统一约束

referral 独立业务库和应用/迁移账号，实例复用 dev_infra MySQL。所有业务表主键/唯一键包含 `tenant_id`；主体使用内部规范化 `subject_token`（最多 256），客户端不接触原值；业务查询以租户和组织范围过滤。UTC 时间采用 `datetime(6)`，Java 使用 Instant 并测试时区转换。ID 用 UUID 字符串或确定性摘要，外部 sourceRequestId ≤128。

下列是v1逻辑摘要，不是可执行DDL。v2完整字段、主体摘要索引、正交奖励状态、分桶配额、角色/任务/授权receipt和加密幂等表以DATABASE_DESIGN为准。实施迁移时每张表和每一列都写中文COMMENT；不改已运行的V1/V2迁移。金额使用bigint最小单位；数量和计数不可为负。

### 4.2 referral-service 新建表

基础公共字段：`tenant_id varchar(64)`、对应 ID、`created_at/updated_at datetime(6)`。需要并发变更的实体含 `row_version bigint`。以下列出各表额外字段和关键约束。

| 表 | 主要字段 | 约束/索引与用途 |
|---|---|---|
| `mk_referral_participant` | participant_id、campaign_id、organization_id、shop_id、inviter_subject、definition_id/version、generation、status | UNIQUE(tenant,campaign,inviter_subject)；固定邀请人规则版本，是统计和阶梯的聚合根 |
| `mk_referral_invite_token` | token_id、token_hash、participant_id、expires_at、revoked_at、token_version | UNIQUE(token_hash)；随机至少 128 bit；库中不存明文；不在 token 内直接暴露用户ID |
| `mk_referral_relation` | relation_id、campaign_id、participant_id、invitee_subject、bound_at、qualify_deadline、status、reason_code、policy_hash | UNIQUE(tenant,campaign,invitee_subject)；INDEX(tenant,participant,status,bound_at,relation_id)；版本取 participant |
| `mk_referral_evidence` | evidence_id、source_system、business_key、subject_token、fact_type、aggregate_version、occurred_at、payload_hash、normalized_json | UNIQUE(tenant,source_system,fact_type,business_key,aggregate_version)；索引主体/业务键；保存权威会员、订单、退款证据，支持先事件后绑定 |
| `mk_referral_qualification` | qualification_id、relation_id、goal_type、order_id、evidence_revision、net_amount_minor、currency、settle_after、state、risk_decision_id | UNIQUE(tenant,relation,goal_type)；INDEX(tenant,state,settle_after,id)；一个关系一份资格，可更新证据修订，不能靠重复消息造多个资格 |
| `mk_referral_progress` | participant_id、valid_count、lifetime_qualified_count、last_revision | PK(tenant,participant)；当前有效人数可下降，历史次数只用于审计，不驱动重复奖 |
| `mk_referral_reward` | reward_id、campaign_id、participant_id、relation_id（可空）、beneficiary_subject、role、rule_id、milestone_key、benefit_definition_version、sku_version、quantity、state、entitlement_revision、source_request_id、award_intent_id、award_order_no、last_error | UNIQUE(tenant,campaign,beneficiary_subject,role,rule_id,milestone_key)；source_request_id 唯一；逐人 milestone=relationId，阶梯 milestone=threshold，禁止 NULL 唯一键漏洞 |
| `mk_referral_reward_attempt` | attempt_id、reward_id、attempt_no、authorization_digest、risk_decision_id、attempt_state、next_retry_at、lease_owner/until/version | UNIQUE(tenant,reward,attempt_no)；保留风控复评和投递尝试；reward 身份不随重试改变 |
| `mk_referral_quota_account` | campaign_id、rule_id、quota_limit、reserved_count、consumed_count、version | UNIQUE(tenant,campaign,rule)；reserved+consumed≤limit；仅活动发券数量配额，不能代表权益中心资金余额 |
| `mk_referral_quota_reservation` | reservation_id、reward_id、quantity、state、expires_at | UNIQUE(tenant,reward)；与 reward 同事务占用额度；受理中/结果未知不可因超时自动释放 |
| `mk_referral_compensation` | compensation_id、reward_id、cause_evidence_id、revision、state、external_reversal_id、attempts、next_retry_at、last_error | UNIQUE(tenant,reward,revision)；取消/追回任务，保留人工未解决状态 |
| `mk_referral_inbox` | source_system、event_id、payload_hash、received_at | PK(tenant,source,event)；与业务变更同事务提交；另有业务证据唯一键防新 eventId 重放 |
| `mk_referral_outbox` | event_id、aggregate_id、aggregate_revision、topic、partition_key、payload、attempts、next_retry_at、lease_owner/until/version、published_at、dead_lettered_at | UNIQUE(tenant,event)；按 next_retry_at 扫描；遵循现有 relay 租约和失败隔离模式 |
| `mk_referral_audit` | audit_id、actor_id、action、resource_ref、reason、before_hash、after_hash、trace_id | 记录绑定拒绝、资格改变、取消、复评、暂停/恢复；仅追加 |
| `mk_referral_runtime_generation` | environment/cell/namespace、generation、manifest_json、artifact_id/payload/hash、key_id、installed_at | PK(tenant,slot,generation)；保留被活跃关系引用的版本 |
| `mk_referral_runtime_slot` | environment/cell/namespace、active_generation、activation_sequence、directive_json、updated_at | PK(tenant,slot)；只能按更高 activation sequence 切换 |
| `mk_referral_runtime_kill_switch` | namespace、switch_sequence、enabled、reason、signed_directive | 单调序号；在绑定、资格确认、凭证签发检查 |

另复用平台持久化 API 幂等执行器的表结构，为 referral 独立库新增对应迁移。所有外部 HTTP 调用位于数据库事务外；数据库不创建指向其他服务库的外键。

### 4.3 修改现有表

| 服务/迁移建议 | 内容 |
|---|---|
| control：`V4__referral_campaign.sql` | `mk_campaign` 增 `campaign_type varchar(32) NOT NULL DEFAULT 'STANDARD' COMMENT ...`；新值 REFERRAL。`mk_definition_version` 当前 dialect 为 varchar，无须新建配置主表；仍复用版本、条款、审批与审计。校验一个活动的 referral 主定义绑定唯一性 |
| benefit：`V10__referral_award_authorization.sql` | 新增 `mk_award_authorization` 保存 issuer、reward_id、policy/artifact hash、凭证摘要、受益人 hash、revision、expiry 和使用记录；扩展意图读模型的资格来源关联。现有去重键已含 source_system，但代码必须改为显式来源 |
| benefit：`V11__award_fulfillment.sql` | 新增 `mk_award_fulfillment`（intent、order、item、status、provider_revision）、`mk_award_result_inbox` 和 `mk_award_compensation`；不要把 outbox.SENT 重命名为最终成功 |
| benefit：`V12__referral_risk_attempts.sql` | 新增 `mk_referral_award_evaluation`，按 tenant/reward/attempt 唯一保存风控结果、复评依据和租约；奖励级别唯一授权记录裁决最终出箱。保留旧 mk_award_intent_block 的不可改首次结果语义 |
| measurement：下一可用版本 | 新增 referral 专用漏斗/奖励汇总投影与处理位置；避免对现有事件 enum 的变更让旧消费者无法反序列化 |

迁移号按实施时最新版本顺延。旧活动默认 STANDARD，历史记录与 OfferToken 发奖行为保持兼容；升级服务后再允许提交新方言。

## 5. 规则定义、低代码和发布

### 5.1 新增方言和配置

新增 `Dialect.REFERRAL_POLICY`，编译产物类型 `REFERRAL_PLAN`，ABI `marketing-referral-plan/1`，runtime=`referral`。先提供表单式设计器；服务端存储结构化 GraphDefinition，供后续画布复用。

沿用当前 `GraphNode.config: Map<String,String>`：每个奖励档位一个节点，整数/日期等以字符串进入现有契约，编译时严格转换为类型化 ReferralPlan；不把对象数组直接塞入现有字符串 Map，也不在首期泛化全部旧方言的 config 类型。

新增节点：

| stableTypeId | 配置（关键字段） | 编译语义 |
|---|---|---|
| `referral.start` | startsAt、endsAt、settlementEndsAt、organizationId、shopId | 活动时间和范围 |
| `referral.bind` | attribution=FIRST_VALID_BIND、maxBindAgeSeconds、inviteeScope | 固定关系和归因 |
| `referral.qualify` | goalType、qualificationWindowSeconds、minNetAmountMinor、currency、observationSeconds | 新客/首单及退款观察期 |
| `referral.reward` | ruleId、role=INVITER/INVITEE、mode=PER_RELATION/MILESTONE、threshold、benefitDefinitionVersion、skuVersion、quantity、perSubjectLimit、campaignLimit | 声明奖励资格；编译阶段无发奖副作用 |
| `referral.end` | 无 | 定义结束 |

首期图限制为 start→bind→qualify→一个或多个 reward→end；reward 顺序代表配置顺序，不表示给邀请人发完才给好友发；禁止循环、任意 webhook、任意脚本和奖励角色自由字符串。ruleId 跨版本稳定，删除后不可复用旧 ID 表示另一种奖励。

例：绑定首单模式，配置 INVITEE/PER_RELATION 一张券、INVITER/PER_RELATION 一张券、INVITER/MILESTONE/3 一张券、INVITER/MILESTONE/5 一张券。SKU 从接口选择，具体值由种子 SQL/运营配置提供。

### 5.2 校验、仿真、发布修改

1. `DefaultNodeRegistry` 注册五种节点（reward可重复）；`GraphValidator` 的纯方言判断加入 REFERRAL_POLICY，全部节点 SideEffect.NONE；新增 `ReferralPolicyValidator` 检查线性拓扑、必需字段、数量/期限边界、奖励唯一性及 SKU 版本。
2. `ControlApplicationService` 支持新 dialect；`GovernanceValidator` 将奖励规则、封顶、退款政策纳入风险摘要和冻结条款；`GraphSimulationService` 增邀请、首单、退款输入，输出关系状态、人数、待发/取消/追回奖励及原因，不产生实际副作用。
3. compiler 新增 `ReferralPlanCompiler`；使用现有 CanonicalGraphHasher、ArtifactAttestation 和签名框架。校验、仿真、实际资格判断共享类型化规则 evaluator，避免三份逻辑漂移。
4. `ReleaseApplicationService` 新增 referral 的明确制品/ABI校验和 SKU release gate。目前 gate 仅检查 decision，必须扩展；权益目录不能验证则禁止激活。
5. 新 runtime 完成预热、验签、ACK、激活、重启加载和 reconcile；namespace 与 campaign 明确一一映射并验证。初期只允许 canaryBasisPoints=0，按租户/活动试点，不做同一参与者随机切版本。
6. benefit 验证奖励凭证时同样验证发布制品及规则引用，不能只相信 referral 服务写入的任意 SKU。通过受信服务接口获取并缓存发布证明，校验编译/发布签名及 sourceDigest；停用/撤销状态按可配置新鲜度刷新。
7. 下线/回滚只影响新加入者的版本选择；已有关系依原计划结算。安全 kill switch 同时暂停新绑定和未执行发奖；它不自动撤回已经到达权益中心的订单。恢复仍检查资格和有效期。
8. 旧制品至少保留至所有关系结算/补偿/对账结束；不能仅凭“已回滚”清理历史资格依据。

## 6. API 与身份契约

运营 API 走 edge-gateway；内部 API 不加入公网路由。所有写接口要求 Idempotency-Key，同键不同请求返回 409；GET 列表按时间+ID 游标分页，绑定/详情必须校验租户、组织和主体归属。

### 6.1 运营侧

| 方法/路径 | 新增/修改 | 作用 |
|---|---|---|
| POST/GET `/api/v1/campaigns` | 修改 | 创建/返回 campaignType=STANDARD/REFERRAL；缺省 STANDARD |
| POST `/api/v1/definitions` | 修改 | 存 REFERRAL_POLICY，继续使用已有版本机制 |
| 现有 definition 的 `:validate/:simulate/:submit` 与 approvals | 修改 | 支持裂变校验、仿真、冻结条款 |
| 现有 `/api/v1/compile`、releases 暂存/激活/回滚 | 修改 | REFERRAL_PLAN、referral runtime；不新增旁路发布接口 |
| GET `/api/v1/referral-campaigns/{campaignId}/participants` | 新增 | 邀请人及有效邀请进度 |
| GET `/api/v1/referral-campaigns/{campaignId}/relations` | 新增 | 脱敏关系、资格状态、证据原因 |
| GET `/api/v1/referral-campaigns/{campaignId}/rewards` | 新增 | 奖励资格、风控、受理、成功、追回；关联 awardIntent |
| GET `/api/v1/referral-campaigns/{campaignId}/summary` | 新增 | 链接访问、绑定、有效邀请、奖励成功、退款失效，带统计水位 |
| POST `/api/v1/referral-rewards/{rewardId}:reevaluate` | 新增 | 受权限控制的证据/风控复评；不接受金额/SKU，不允许绕过 REJECT |
| GET `/api/v1/award-intents?campaignId=...` | 兼容扩展 | 增 sourceSystem、rewardId 和 fulfillmentStatus 可选字段；旧 status 语义不变 |

权限建议新增 `referral:read`、`referral:reevaluate`，配置/审批/发布继续使用现有定义与发布权限。审计记录操作人和原因；界面没有任意“提交发奖”表单。

### 6.2 C 端 BFF 到本系统

| 方法/路径（建议新增内部合同） | 请求 | 返回 |
|---|---|---|
| POST `/internal/v1/referral/participants` | campaignId + 可信用户断言 | participantId、固定版本、活动状态 |
| POST `/internal/v1/referral/invite-tokens` | participantId + 用户断言 | 一次返回明文 inviteToken、expiresAt；分享 URL 由受控 BFF 域名拼装 |
| POST `/internal/v1/referral/invites:resolve` | inviteToken + BFF 机器身份 | 活动简介、脱敏邀请人、条款版本；不创建关系 |
| POST `/internal/v1/referral/bindings` | inviteToken + 被邀请人断言 + consentVersion | relationId、boundAt、资格状态；inviter/version 由 token 服务端还原 |
| GET `/internal/v1/referral/me?campaignId=...` | 可信用户断言 | 我的进度、档位、脱敏好友达标状态、奖励结果 |

用户断言必须由可信 BFF/身份适配器签发并验证 issuer、audience、过期时间、tenant、subject、请求范围；机器 Token 标识调用应用，用户断言标识实际参加者。禁止从浏览器传入 X-Dev-* 或未签名 subjectRef 就认定身份。BFF 匿名 resolve 只暴露已发布公开条款，并限速；所有绑定和领奖状态查询要求登录。

未接入真实 C 端项目时，可在测试环境提供测试 BFF/fixture 做契约验证，但不据此宣称 C 端已上线。

### 6.3 奖励授权内部接口

新增 POST `/internal/v1/referral-award-intents`，请求 `{sourceRequestId, authorizationToken}`；由 referral 的 outbox relay 调用。新入口固定 sourceSystem=`marketing-referral` 且只能运行 CENTER 模式；LEGACY/SHADOW 不能产生本次正式奖励成功态。

referral-service 同时新增 POST `/internal/v1/referral/rewards/{rewardId}:confirm-authorization`，仅 benefit 机器身份可调用，请求包含 sourceRequestId、qualificationRevision 和稳定声明摘要；短事务锁定 reward 并登记授权消费。相同请求重放原结果，已失效或修订不符返回409。新增 GET `/internal/v1/referral/runtime/proofs/{artifactId}` 提供签名发布证明及所属规则摘要；机器身份与租户范围均需验证。

新增 `ReferralAwardAuthorizationClaims/Codec`：tenant、issuer、audience、rewardId、sourceRequestId、campaignId、organization/shop、definitionId/version、generation、artifactId/hash、participantId、relationId 或 milestone、beneficiarySubject、role、ruleId、quantity、qualificationRevision、issuedAt/expiresAt、keyId。签名使用当前 Ed25519 基础设施。

规则：

- 凭证只由锁定的资格账本生成，benefit 校验签名、受益人、租户、服务身份、不可变规则与 SKU 引用；SKU/数量必须能由冻结规则重建，首期不接受现金字段。
- 短期凭证失效后可针对同一 reward 重新签发，但 sourceRequestId 不变。幂等 hash 取稳定业务声明，排除重签时间、签名等易变字段；重新签发不能改奖励内容。
- 首次授权受理前经认证资格确认接口校验 reward 仍有效及 revision，登记消费授权；取消与授权确认竞争须在 referral 数据库中串行化。确认后退款走补偿，不假设短期签名能撤销在途请求。
- 若已受理，重放返回原意图，不因凭证过期重复发奖；先核对调用服务和稳定身份，跨主体复用同键拒绝。
- 来源隔离贯穿 claim、去重、block、outbox、查询和 expected-fact。外部权益中心幂等 Header 暂仅用 sourceRequestId，因此本次采用 `referral:` 前缀+确定性摘要避免与旧来源碰撞，并确认外部 sourceSystem 白名单支持新来源。

## 7. 事件合同与可靠性

在 `marketing-contracts` 增 Java records、JSON Schema 与 AsyncAPI；以下 Topic 均为建议新增，尚未创建。

| Topic | 事件 | 分区/消费 |
|---|---|---|
| `mk.referral.input.v1` | CUSTOMER_REGISTERED、ORDER_SETTLED、ORDER_REFUNDED、CUSTOMER_MERGED（首期合并可进入待复核） | tenant+subject；referral 规范化证据，映射 relation |
| `mk.referral.fact.v1` | RELATION_BOUND、QUALIFICATION_CHANGED、REWARD_ENTITLED、REWARD_CANCELLED、REWARD_FULFILLED、REWARD_REVERSED | tenant+participantId；measurement/可选 Journey |
| `mk.award.fulfillment.v1` | 权益中心最终履约和撤回结果的营销规范化事实 | tenant+sourceSystem+sourceRequestId；referral 更新 reward |

通用 envelope：eventId、tenantId、sourceSystem、schemaVersion、occurredAt、aggregateId/version、traceId；payload 带业务键、subjectToken 和必要证据。订单事件带 orderId、权威首单标识、净额、币种、结算时间和累计退款或可唯一去重的 refundId。

修改 EventPayloadRouter 增 `REFERRAL_FACT_INPUT` 外层路由；不能只写一个 eventType 就被接受。EventIngestionService 需要把认证调用方与注册 sourceId 绑定，校验允许的事实类型及 Schema，确保普通参加者不能伪造 ORDER_SETTLED。平台事实统一 sourceSystem，跨系统同名 eventId 不互相覆盖。

按至少一次投递设计：inbox、证据更新、资格/进度/reward 和 outbox 同一事务；事务提交后才确认 Kafka offset。重放相同 eventId/hash 无副作用，不同 hash 冲突隔离。不同 eventId 的同订单修订由 business key+revision 去重。

退款早于支付：保存退款证据，等待订单快照/重查，不能先忽略退款后给全额达标。旧 aggregateVersion 不覆盖新状态；非累计退款需按 refundId 累加并控制最大退款金额。过早事件保留，绑定后重新检查窗口。迟到但发生在合法窗口的事件，在配置宽限期内按 occurredAt 处理，超期进入可审计待复核。

注册奖励需要会员权威注册时间；“事件晚到”可以支持，“早已注册但点击邀请链接”不自动成为新客。事件保留和资格依据至少覆盖结算、追回、重放窗口。

measurement 首期消费独立 referral topic 构建漏斗，保持原 MarketingFact enum；共享总成本报表只由单一权益履约事实计入，不能 reward entitled 和 fulfilled 各算一次成本。若将来扩 MarketingFact，必须先升级所有消费者再启用新枚举。

## 8. 状态机、并发与退款

### 8.1 关系与资格

关系：BOUND → PENDING_QUALIFICATION → QUALIFIED；到期进入 EXPIRED；证据撤销进入 INVALIDATED；风险命中进入 BLOCKED/REVIEW。具体证据、拒绝原因保存在 qualification 与 audit，避免单个状态无法解释。

邀请人不能邀请自己。数据库唯一键裁决不同邀请人同时绑定同一好友的竞争；重试同一绑定返回原关系，不同邀请人返回 `REFERRAL_ALREADY_BOUND`。首期禁止直接互邀，并按权威新客条件限制参加角色；不对整个邀请图做递归发奖。

### 8.2 一次达标事务

1. 根据 relation 找到 participant，按固定顺序锁 participant/progress，再锁 qualification/relation；所有达标和退款路径遵循相同顺序。
2. 仅当资格从无效→有效，valid_count 加一；仅有效→无效减一。重复事实不改变计数。
3. 比较修改后的有效人数与规则；按稳定 reward 唯一键创建逐人奖、好友奖、跨越的阶梯奖。同一事件从 2 到 5 人的重建场景可补齐 3、5 两档，但已存在的档位不可重发。
4. 锁配额账户，条件更新占用名额并插入 quota_reservation/reward/outbox；额度不足记录 QUOTA_EXHAUSTED，不静默丢弃资格。默认不因日后释放自动补发，是否重评由冻结政策明确。
5. 提交。发前风控、签名资格确认、外部履约在后续步骤完成。

业务唯一键不包含新签名、重试次数或任意前端requestId。v2替换以上第4步的单活动配额热行：使用预分配bucket、个人限额、fencing和reservation同事务预占；总账户仅用于冷路径调拨。完整算法与容量边界见PRODUCTION_DESIGN第6.4节。

### 8.3 奖励与风控

以上业务含义在v2实现为五个正交状态字段：entitlement、authorization、risk、delivery、compensation；不直接实现一个混合全部含义的长枚举。状态合法值及UI映射见PRODUCTION_DESIGN第6.6节。

外部受理超时记 UNKNOWN/待对账，不推断失败；用相同 sourceRequestId 查询/重试。权益中心返回 202 只到 ACCEPTED。一个受益人一份 reward，双边奖励两份；一边失败不抹掉另一边的成功。

扩展风控上下文为 referral 专用端口：scene、participant/relation/rewardId、inviter/invitee 主体摘要、可信设备指纹摘要、账号年龄、新客证据、短时绑定频率、金额/券类型。设备/IP 必须来自可信 BFF 或服务端，不把客户端自报字段当风控事实。现有 RiskEvaluationGateway 只提供交易维度，外部是否支持上述字段需联调。

保留旧 OfferToken 入口的首次风控结果语义。本次对暂时不可用使用 reward_attempt 保存尝试，重评共享同一 reward；明确 REJECT 不自动改用新 sourceRequestId 绕过；REVIEW/CHALLENGE 需要可信案件结果后再次评估。通用复用层只负责租约/持久化，不偷偷改变既有 4b 行为。

具体持久化边界：referral 的 reward_attempt 跟踪编排尝试；benefit 的 mk_referral_award_evaluation 保存实际发前风控尝试。新来源的待评估/拦截从新表读取，第一次获准后才写稳定的 award-intent outbox 与去重结果，不先写旧 BLOCK 再改成 OUTBOX。运营旧列表兼容加入该来源的当前评估投影，按 reward 只显示一条当前记录，审计详情保留所有尝试；旧 drools-activity 路径仍使用原 block 表及重放策略。

### 8.4 退款/撤销规则

重新计算订单净额与资格；仍达门槛不减人数，低于门槛才失效。逐人奖励按对应 relation 取消/追回；阶梯奖励在当前人数低于该档位时进入取消/追回。已有或已追回的同一档位仍保留唯一身份，之后重达不再发；运营条款必须展示这个首期规则。

- 未授权：同事务取消 reward、释放未投递数量配额。
- 已授权/投递中：置 CANCEL_PENDING，先核对外部是否受理；未确认前不释放配额。
- 已成功：创建补偿任务调用权益中心撤回合同；不可撤回（如券已核销）进入 MANUAL_REVIEW，不伪记 REVERSED。
- 外部最终明确未发放或撤回成功才按冻结政策释放配额；已消费权益默认不返还活动名额。
- cancel 与 success 回调交叉：保留履约事实及补偿事实，成功回调不能把 CANCEL_PENDING 擦除，应继续触发追回。

本仓库尚未确认权益中心订单终态查询/事件/撤回 API。新增的是适配器和合同要求，不直接假设已有 `/cancel`。若外部没有撤回能力，可完成资格与待发取消；涉及已发奖励的正式活动必须采用业务明确认可的不可追回政策或补齐外部功能。

## 9. 精确到模块和文件的工作清单

表中“新增”均为拟新增文件；“修改”均为已存在文件。Java 新类、公共方法、关键分支写中文注释，说明归因、锁顺序、幂等和状态选择的原因。

### 9.1 新增 referral-service

以 `services/referral-service/` 为根，包名 `com.acme.marketing.referral`：

| 子目录 | 新增文件 |
|---|---|
| 根/src/main/java | `pom.xml`；`ReferralApplication.java` |
| `domain/` | `ReferralParticipant.java`、`ReferralRelation.java`、`ReferralQualification.java`、`ReferralReward.java`、`ReferralQuota.java` |
| `application/` | `ReferralParticipationService.java`（加入/token/绑定）；`ReferralQualificationService.java`（事实+观察期）；`ReferralRewardService.java`（资格/档位）；`ReferralCompensationService.java`（撤销）；`ReferralRepository.java`；`ReferralRiskGateway.java`；`CustomerEvidenceGateway.java`；`OrderEvidenceGateway.java`；`ReferralRuntimeReleaseService.java`；`ReferralAuthorizationService.java` |
| `interfaces/` | `ReferralOperationsController.java`、`ReferralParticipationController.java`、`ReferralAuthorizationController.java`、`ReferralRuntimeController.java` |
| `infrastructure/` | `ReferralConfiguration.java`、`ReferralInputConsumer.java`、`AwardFulfillmentConsumer.java`、`ReferralOutboxRelay.java`、`ReferralSettlementWorker.java`、`ReferralReconciliationWorker.java`、`ReferralReleaseConsumer.java`、`ReferralKillSwitchConsumer.java`、`BffSubjectAssertionVerifier.java`、会员/订单/风控 HTTP 适配器 |
| `infrastructure/persistence/` | `MybatisReferralRepository.java`、`mapper/ReferralMapper.java` |
| `src/main/resources/` | `application.yml`、`mapper/ReferralMapper.xml`、`db/migration/V1__referral_core.sql`、`V2__referral_reliability.sql`、`V3__referral_runtime.sql`、`V4__api_idempotency.sql` |
| `src/test/java/.../` | `ReferralBindingIntegrationTest.java`、`ReferralQualificationIntegrationTest.java`、`ReferralRewardIntegrationTest.java`、`ReferralRefundIntegrationTest.java`、`ReferralRuntimeReleaseIntegrationTest.java`、`ReferralIdentityTest.java` |

### 9.2 公共合同、编译与控制面

| 现有路径/模块 | 新增/修改内容 |
|---|---|
| `services/pom.xml`、`runtime-spi/pom.xml` | 注册 referral-service 与 referral-runtime-spi 模块 |
| 新增 `runtime-spi/referral-runtime-spi/` | pom；`ReferralPlan.java`、`ReferralRewardRule.java`、`ReferralPolicyEvaluator.java`、`ReferralPolicyValidator.java`；对应单测；提供确定性规则运算，不访问数据库 |
| `runtime-spi/lowcode-language-core/.../model/Dialect.java`、`validation/GraphValidator.java` | 新方言和纯图校验 |
| `services/rule-compiler-worker/pom.xml`、`application/RuleCompilerService.java` | 新编译格式、模块依赖；新增 `application/ReferralPlanCompiler.java` 及编译测试 |
| `services/marketing-control-service/pom.xml` | 引入 referral-runtime-spi 用于校验/仿真 |
| control `domain/Campaign.java`、`interfaces/ControlController.java` | campaignType 枚举/DTO，默认 STANDARD |
| control `application/ControlApplicationService.java`、`ControlRepository.java`、`infrastructure/persistence/MybatisControlRepository.java`、`mapper/ControlMapper.java` 与资源 XML | 新字段读写、类型一致性、主定义绑定 |
| control `DefaultNodeRegistry.java`、`GovernanceValidator.java`、`GraphSimulationService.java` | 新节点、业务校验、风险摘要、仿真；新增对应测试 |
| control `ReleaseApplicationService.java`、`BenefitReleaseGate.java`、`HttpBenefitReleaseGate.java` | referral 制品、授权证明、SKU 版本和 runtime readiness；必要时扩展内部 SKU gate DTO |
| `marketing-contracts/src/main/java/com/acme/marketing/contracts/` | 新增 referral 包：输入事实、状态事实、奖励授权 Claims/Codec、履约结果合同 |
| `marketing-contracts/src/main/resources/openapi/marketing-api.yaml` | 新接口、错误码、权限、请求/响应、可空与兼容说明 |
| `marketing-contracts/src/main/resources/asyncapi/marketing-events.yaml` | 三个新 topic、来源、键、重放与 schema |
| `marketing-contracts/src/main/resources/schema/graph/graph-definition.schema.json` | 新方言枚举；新增 referral 节点与 plan schema |
| `marketing-contracts/src/test/.../ContractSpecificationsTest.java`、`frontend/packages/contracts/src/index.ts` | 合同校验、类型同步；更新所有枚举 switch 和 schema 测试 |

### 9.3 benefit、events、measurement 与可选触达

benefit 根为 `services/benefit-funding-service/src/main/java/com/acme/marketing/benefit/`：

| 文件 | 改动 |
|---|---|
| 新增 `interfaces/ReferralAwardIntentController.java` | 仅可信裂变来源的新入口 |
| 新增 `application/ReferralAwardIntentAssembler.java`、`ReferralAwardAuthorizationVerifier.java`、`ReferralAwardIntentService.java` | 验证可信资格、规则/SKU重建；CENTER-only；复评与稳定去重 |
| 新增 `application/AwardIntentPersistenceService.java`（名称可按实现调整） | 抽取多来源短事务/租约/入箱出箱能力；明确 sourceSystem、稳定业务哈希，不耦合旧 AssembleCommand |
| 修改 `application/AwardIntentService.java`、`AwardIntentRepository.java`、相关 MyBatis 实现/Mapper/XML | 将写死 SOURCE_SYSTEM 的路径参数化；旧入口仍固定 drools-activity，并保持原测试与风险结果语义 |
| 保留 `application/AwardIntentAssembler.java` 的 OfferToken 验证 | 不让 referral 调用方传任意主体覆盖旧 token；新来源使用新 assembler |
| 新增 `application/AwardFulfillmentService.java`、`BenefitOrderStatusGateway.java`、`AwardCompensationService.java`、`BenefitOrderReversalGateway.java` | 受理后状态查询/结果处理和撤回适配；外部路径以联调合同为准 |
| 新增 `infrastructure/AwardFulfillmentConsumer.java` 或签名回调 Controller | 根据外部支持确定一种主接入；inbox、订单版本去重、单调状态及定期对账兜底 |
| 修改 `infrastructure/AwardIntentRelay.java`、`application/AwardIntentRelayRepository.java`、持久化映射 | 回传受理事实，新增最终结果回流；来源命名空间；沿用 CAS 租约，不把 202 当成功 |
| 修改 `application/RiskEvaluationGateway.java` 或新增专用 Referral 风控端口 | 旧合同不支持的上下文采用新版本适配，不静默丢字段 |
| 修改 `BenefitConfiguration.java`、`application.yml`、迁移和测试 | 可信 issuer/key、runtime证明查询、新来源权限、状态查询与回调配置 |

event gateway 修改 `EventPayloadRouter.java`、`EventIngestionService.java`、`EventIngestionRepository.java` 及对应 Mapper/迁移，增加认证 principal 与 sourceId 授权绑定、新 schema 校验和定向路由；补伪造订单事实测试。

measurement 新增 `ReferralMeasurementConsumer.java`、`ReferralMeasurementProjection.java`、查询 DTO/Mapper 与迁移；修改 `services/measurement-service/src/main/java/com/acme/marketing/measurement/interfaces/MeasurementController.java` 或新增独立 Controller，提供带水位的裂变汇总；referral 的 summary 接口通过内部查询端口读取该汇总，不跨库读表，measurement 不可用时明确返回暂不可用而非零值；消费者幂等并支持资格反转。

Journey 首期不新增跨用户计数和发奖节点。可选新增 `ReferralJourneyBridge.java`，把已确认的 referral 事实转换为现有 JOURNEY_SIGNAL，使用明确签名发布计划引用；对邀请人和好友分别创建实例，副作用只用于通知。没有 bridge、真实模板/同意记录时，邀请有礼的奖励核心仍可独立工作，通知功能不得宣称上线。

### 9.4 前端交付给 Cursor

| 现有/新增文件 | 任务 |
|---|---|
| 修改 `frontend/apps/console/src/features/campaign/CampaignsPage.tsx` | 类型选择/筛选，REFERRAL 打开裂变设计器；STANDARD 保持原路由 |
| 新增 `features/referral/ReferralDesignerPage.tsx` | 基本信息、参与/归因、新客/首单、双边/阶梯券、封顶、退款政策、仿真 |
| 新增 `features/referral/ReferralOperationsPage.tsx` | 参与人/关系/奖励/异常页签；明示受理与成功的区别，复评按权限展示 |
| 修改 `features/campaign/CampaignReadiness.tsx` | 按类型计算就绪，REFERRAL 不被强制要求 Offer；检查规则/SKU/身份/事件源/runtime |
| 修改 `features/release/ReleaseWizard.tsx`、`ReleaseCenterPage.tsx` | 新 runtime/format、SKU 门禁、ACK 与不支持灰度的提示 |
| 修改 `src/shared/api/{client,schemas}.ts`、`src/shared/model/types.ts` | 新 DTO、方言、分页与错误码 |
| 修改 `src/App.tsx`、`src/app/navigation.ts` | `/designers/referral`、`/referral-operations` |
| 修改 `features/operations/AwardIntentsPanel.tsx` | 显示 referral 来源、最终履约/追回状态与关联链接 |
| 新增 C 端页面（外部项目路径待提供） | 活动/条款、邀请链接、好友绑定确认、我的进度/奖励；使用真实 BFF API |

所有列表/进度/券来自接口。Mock 写种子 SQL，不在页面添加假成功数据；loading、空态、活动结束、链接失效、非新客、已绑定、风控待审、退款失效都要有真实状态映射。

### 9.5 构建、网关与基础设施

- 修改 `services/edge-gateway/src/main/resources/application.yml`：新增 `/api/v1/referral-campaigns/**,/api/v1/referral-rewards/**` 路由到 referral；新增 circuit breaker 和限流。内部参与/发奖 API 不对公网转发。
- 修改 `deploy/compose.yaml`、`deploy/compose.secure.yml`、`deploy/helm/marketing-platform/values.yaml`、`values-production.example.yaml` 与 `templates/services.yaml/networkpolicy.yaml`：新服务、数据库账号、Kafka ACL、机器身份、配置、health、网络授权。
- 修改 `.env.example`、`scripts/bootstrap.sh`、`scripts/_lib.sh`、`scripts/dev-up.sh`、`scripts/seed.sh`、`scripts/verify-compose-model.py`、`scripts/verify-deployment.sh`、`scripts/verify-mysql-migrations.sh` 中的服务枚举/数据库映射；已有通用模板无需无意义复制。
- 外部 dev_infra：数据库初始化增加 referral 独立库/账号，Kafka 初始化增加 topic/ACL。这是跨仓库交付项，需在实际 dev_infra 仓库实施，本文未修改它。
- 修改 `.github/workflows/ci.yml`、`full-stack.yml`、`images.yml` 中需要枚举服务/镜像/迁移的部分。复用现有镜像基线和 JDK；不在该功能中升级框架版本。
- 新增 `scripts/seed-data/referral-mock.sql`、`scripts/local-referral-smoke.py` 和 `tests/performance/referral-ingest.js`；种子包含 A/B/C、有效/失效邀请、3/5人阶梯、退款、风险待审等完整关联数据。
- 文档同步 `README.md`、`docs/domain/MODEL.md`、`docs/contracts/API_AND_EVENTS.md`、`docs/lowcode/RUNTIME.md` 和部署说明，明确现在是六方言及新增 runtime。

## 10. 外部依赖与交付边界

| 外部系统 | 必须提供的合同/变更 | 缺失时的处理 |
|---|---|---|
| 会员/身份/BFF | 规范主体、权威新客/注册时间、签名用户断言、账号合并政策 | 可做本地合同测试；不能用管理端账号冒充真实 C 端能力 |
| 订单 | 首单标识、结算/累计退款快照、单调版本、按业务键查询 | 首单活动不能上线；不能把缺失查询默认判定达标 |
| 权益中心 | marketing-referral 来源授权、固定券版本、sourceRequestId 幂等、订单终态查询/事件、撤回及不可撤回原因 | 没有终态只能显示已受理；没有撤回按第8节限制验收 |
| 风控平台 | 裂变风险场景、关联主体/设备上下文、案件复评结果 | 本地拒绝自邀/重复邀请仍可做；关联反作弊必须经真实合同验收 |
| dev_infra | 独立数据库账号、topics/ACL | 本仓库配置完成不等于依赖已创建 |
| Cursor/C 端项目 | 管理端改造和真实参与页面、BFF路由 | 后端合同完成不等于用户可参加 |

首期只维护活动发券数量配额，券可用性和实际权益库存以权益中心为权威。如果要提供金额预算封顶，需新增预算预占/确认/释放合同并指定唯一账本所有者，不能把本地计数冒充资金预算。此项作为现金/复杂券成本下一期的前置条件。

## 11. 分阶段实施与验收

不逐阶段要求输入“继续”。在业务口径和整个实施方案确认后，按依赖顺序持续完成；遇到外部合同缺失，先完成不依赖该合同的任务并标明剩余交付。

| 阶段 | 新增/修改重点 | 完成标准 |
|---|---|---|
| P0 合同与口径 | 新客/首单/退款政策、外部接口 schema、来源/身份权限、券清单 | 有可审阅合同和 fixture；未知外部能力明确标注，不能假造已存在接口 |
| P1 规则与发布 | campaignType、新方言、plan/compiler/validator/simulator、发布 runtime | 创建→仿真→审批→预热→ACK→激活→重启恢复可测；SKU 无效禁止发布 |
| P2 参与与归因 | 核心迁移、BFF身份、token、加入/绑定/我的状态 | 并发绑定唯一、自邀拒绝、版本固定、跨租户/主体访问拒绝 |
| P3 资格与计数 | 会员/订单证据、观察期扫描、资格状态、进度/阶梯、配额 | 乱序/重复/并发/退款均不重复计数；同档位不重复奖励 |
| P4 可信发奖 | 新授权、sourceSystem重构、风控、outbox、权益终态回流 | 双边券真实到账，各有独立状态；超时重放不重复发；旧 OfferToken 流程回归通过 |
| P5 退款与对账 | 取消/撤回、竞态、对账 worker、运营读模型 | 已发可追踪终态，不能追回明确人工态；账本与权益中心一致 |
| P6 前端与联调 | Cursor 管理端/C端、数据库种子、真实合同测试 | 不用硬编码数据，完整用户操作演示；数据水位和异常清晰 |
| P7 发布准备 | CI/部署/压测/告警/回滚文档 | 新旧迁移通过，试点活动验证，暂停/恢复/回滚不改变历史奖 |

原v1粗估后端30–45人日已不能覆盖本轮三高详细设计全部增量。v2包含分桶、角色互斥、持久授权恢复、取消栅栏、规模压测和多AZ演练，应在P0明确基础设施与外部复用情况后重新排期。若压缩首发，可限定单层、固定券、单目标模式；身份、幂等、退款政策、终态对账仍是必需项。

## 12. 必须覆盖的测试

| 编号 | 场景 | 断言 |
|---|---|---|
| R01 | A/D 同时邀请 B，100 并发请求 | 只存在一个活动内归因关系；loser 返回稳定业务码 |
| R02 | 同用户重试、跨租户 token、过期/撤销 token、自邀 | 不产生新关系/越权；明文主体不泄露 |
| R03 | 同订单同事件重复100次，以及换 eventId 重放 | qualification/progress/reward 各只变化一次 |
| R04 | 退款先于订单、旧版本事件晚到、绑定晚于消息 | 净额和资格按权威修订正确重建 |
| R05 | 首单资格消息在观察期到期前/后到达 | 未成熟不发奖；迟到事件按明确截止政策处理 |
| R06 | 2人时两人同时达标、一次重建跨多个档位 | 人数准确；3人/5人各仅一个里程碑奖励 |
| R07 | 达标→退款跌档→再次达档 | 有效人数变化正确；同档位不重复奖 |
| R08 | 配额剩1时并发发奖、进程在事务中崩溃 | 不超配额；重启无半条 reward 或丢 outbox |
| R09 | 篡改凭证主体/SKU/数量/版本、重签过期凭证 | 篡改拒绝；同 reward 合法重签不重复发 |
| R10 | 授权确认与退款同时发生 | 要么取消授权，要么受理后生成补偿，不漏追回 |
| R11 | 风控超时、REJECT、REVIEW后可信解除 | 待处理可恢复；明确拒绝不可借换键绕过 |
| R12 | 权益中心已受理但 HTTP超时、回调重复/乱序 | 同sourceRequestId最多一份外部奖；不把202当成功 |
| R13 | 双边一方失败、券已核销后退款 | 两人状态独立；不可追回进入人工态 |
| R14 | runtime旧签名、回滚、kill switch、重启 | 未可信发布不可发；旧关系版本不变；暂停未发奖 |
| R15 | 老STANDARD活动、旧OfferToken/4b风险路径 | 原身份/金额验证、首次结果重放和运营查询保持兼容 |
| R16 | 统计重放与逆向事实 | 漏斗、人数、奖励和成本不双计，水位可见 |
| R17 | 真实BFF→绑定→首单→观察期→双边发券→退款 | 与权益中心终态核对；仅 WireMock 通过不能替代该验收 |

数据库测试基于现有 `MySqlIntegrationTest` 和真实 MySQL，覆盖锁/唯一键/回滚。契约与风控/权益适配先用受控 stub；生产前 R17 必须在明确的测试环境联调。

实施时预期执行：`./mvnw --batch-mode --no-transfer-progress verify`、`./scripts/verify-contracts.sh`、`./scripts/verify-mysql-migrations.sh`、`./scripts/verify-deployment.sh`；Cursor 执行现有 frontend lint/test/build/e2e，并补裂变用例。本文只做方案核验，未运行上述构建、测试或部署命令。

v2已在PRODUCTION_DESIGN给出参考容量包络，在ACCEPTANCE给出测试阈值；P0按真实业务预测冻结最终目标。测试报告p95/p99、积压恢复、锁等待、重复发放数（必须0）、超配额数（必须0），不能拿已有决策吞吐量替代裂变容量证据。

## 13. 监控、上线与回滚

新增指标：绑定冲突率、资格延迟、观察期扫描滞后、inbox冲突、outbox积压、风控不可用率、授权拒绝、受理未终结、追回失败、配额占用、对账差异。指标标签使用受控枚举，不使用用户/活动ID作高基数标签；明细通过日志 traceId 查询。

上线顺序：先数据库扩展/兼容消费者和外部合同 → benefit新来源/授权验证 → compiler/control → referral runtime → 管理端/C端 → 受控租户活动启用。已有服务滚动升级完成前，REFERRAL_POLICY 开关关闭。

回滚：先关闭新参与和新奖励签发，再暂停新来源投递；保留结果回流/补偿/对账运行。切换代码/规则版本不删除关系、reward、inbox/outbox或迁移。任何已受理奖励都必须跟踪到终态，不能靠回滚数据库字段撤销外部发奖。

## 14. 本轮方案复核记录

本次按需求、代码、方案、反例复核分开检查，由当前 Codex 顺序完成，未进行多代理或跨模型复核。

- 已修正：不能直接调用原 AwardIntentAssembler 处理裂变；它要求 OfferToken 且限定受益人。新增凭证与独立 assembler，同时复用底层可靠投递。
- 已修正：SOURCE_SYSTEM 不是单个配置点，必须覆盖 claim/block/去重/查询；外部 HTTP 幂等键还需要避免跨来源碰撞。
- 已修正：SENT 不等于到账，必须补最终履约状态与对账。
- 已修正：新增枚举会影响 GraphDefinition Schema、编译格式、发布选择、就绪检查；不能只加后端节点。
- 已修正：阶梯唯一键和归因唯一键跨定义版本稳定，邀请人固定版本，避免升级重发。
- 已修正：签名凭证本身不能解决退款撤销，在首次受理前加资格串行确认，之后走补偿。
- 已修正：退款不是简单 valid_count--，还要处理降档、已核销奖励、取消与成功回调竞争。
- 已修正：独立漏斗 topic 避免首期给所有旧 MarketingFact 消费者带来新枚举反序列化风险。
- 待外部确认：真实新客/首单口径、权益中心来源/版本/终态/撤回、风控上下文、C端项目和身份合同；均列为 P0/联调验收输入，不作为已有能力陈述。

## 15. 后续实施入口

建议实施指令：

> 按 `docs/plans/referral-campaign-20260907/FINAL_PLAN.md` 实施邀请有礼后端。先读取根 CODEX_PROGRESS.md（若存在），核对最新代码和迁移号，确认已冻结业务参数与外部合同；按 P0–P7 连续推进，前端输出给 Cursor 的合同与任务单。业务代码、DDL注释、幂等/退款/并发测试和部署配置一并完成，保留外部未联调项的真实状态，不把模拟通过当成上线。每阶段更新本计划目录下 IMPLEMENTATION_PROGRESS.md，接近上下文限制时同步根 CODEX_PROGRESS.md。
