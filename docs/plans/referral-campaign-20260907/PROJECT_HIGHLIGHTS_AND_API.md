# 项目重点与API总览 v2

本表是实施导航，未标“现有”的组件/API都需要新增；现有API也必须按任务扩展后才支持裂变。

## 1. 项目重点

| 重点 | 核心实现 | 为什么关键 | 验收 |
|---|---|---|---|
| 归因唯一 | 主体角色锁、relation唯一键、同活动固定cell | 千并发抢绑定仍只有一个邀请人 | AC01/02 |
| 规则可信 | 固定版本、同源仿真、签名制品、ACK激活 | 页面配置与实际发奖依据一致 | AC11/25 |
| 达标可解释 | 权威会员/订单证据、revision、观察期 | 不把消息先后误当新客/首单 | AC04–08 |
| 阶梯不重发 | participant锁、稳定milestone和reward唯一键 | 并发、退款、版本升级不重复奖 | AC09/10 |
| 高并发名额 | 分桶预分配、用户限额、fencing、守恒账本 | 不争抢全活动热行且不超发 | AC12–14 |
| 可信奖励授权 | 候选签名+持久receipt+按source恢复 | 跨服务超时/崩溃可继续，不能伪造受益人 | AC15/17/18 |
| 风控可恢复 | 策略端口、attempt历史、可信复评 | 技术超时可重试，明确拒绝不可绕过 | AC16 |
| 最终履约与退款 | 正交状态、取消墓碑、Saga、对账 | 202不是到账，退款竞态不漏追回 | AC19–23 |
| 三高保障 | CDN/私有缓存隔离、cell、bulkhead、多AZ | 以容量包络和故障证据证明 | 容量与故障章节 |
| 工程质量 | 表/字段COMMENT、中文Javadoc、合同测试 | 降低维护误解和变更风险 | 注释及迁移门禁 |

## 2. 现有控制面API的修改

| 方法/路径 | 归属 | 变更及调用边界 |
|---|---|---|
| POST /api/v1/campaigns | control | 增campaignType；缺省STANDARD；创建需现有写权限 |
| GET /api/v1/campaigns | control | 返回类型，支持裂变活动导航 |
| POST /api/v1/definitions | control | REFERRAL_POLICY保存、主定义唯一绑定 |
| GET /api/v1/definitions/latest | control | 新dialect查询 |
| GET /api/v1/definitions/{id}/versions/{version} | control | 返回冻结裂变配置 |
| POST /api/v1/definitions/{id}/versions/{version}:validate | control | 必需字段、SKU、规则及时间约束 |
| POST /api/v1/definitions/{id}/versions/{version}:simulate | control | 邀请/达标/退款输入，输出可解释结果，无发奖副作用 |
| POST /api/v1/definitions/{id}/versions/{version}:submit | control | 冻结条款及奖励版本进入审批 |
| POST /api/v1/approvals/{caseId}/decisions | control | 复用审批，展示裂变资金/权益风险 |
| POST /api/v1/compile | compiler | format=REFERRAL_PLAN |
| GET /api/v1/artifacts/{artifactId} | compiler | 新制品及签名证明 |
| POST /api/v1/releases | control | runtime=referral、制品闭包/SKU gate |
| POST /api/v1/releases/{manifestId}:ack | control | 仅runtime机器身份发签名ACK |
| POST /api/v1/releases/{manifestId}:activate | control | 就绪后激活，固定版本 |
| POST /api/v1/releases/{manifestId}:rollback | control | 新sequence引用旧generation，历史关系不迁移 |
| GET /api/v1/releases/desired | control | runtime reconcile |
| PUT /api/v1/releases/kill-switches/{namespace} | control | 停止新参与/新奖励；保持终态与补偿 |
| POST /api/v1/events | event gateway | 增裂变事实路由/来源授权；原eventId去重合同保留 |
| GET /api/v1/award-intents | benefit | 新source/奖励关联/当前风控/最终履约，不改旧status语义 |

现有接口路径已按Controller核验；其原有权限与幂等约定保留。新增业务写要求幂等键，不强行声称现有compile/events接口已经采用相同Header（events使用事件身份去重）。

## 3. 新增运营API

| 方法/路径 | 权限 | 返回/行为 |
|---|---|---|
| GET /api/v1/referral-campaigns/{campaignId}/participants | referral:read | 脱敏参与者、固定版本、当前人数 |
| GET /api/v1/referral-campaigns/{campaignId}/relations | referral:read | 关系/资格/拒绝原因；分页 |
| GET /api/v1/referral-campaigns/{campaignId}/rewards | referral:read | 五维状态、意图和外部订单关联 |
| GET /api/v1/referral-campaigns/{campaignId}/summary | referral:read | 漏斗/奖励汇总+watermark，不可用不返回假0 |
| POST /api/v1/referral-rewards/{rewardId}:reevaluate | referral:reevaluate | `{reason,expectedRevision}`，202待复评，不接受金额/SKU/受益人 |

列表规范：`limit=1..100,cursor?,state?`；响应`{items,nextCursor,asOf}`。运营访问还必须检查组织/店铺范围；只有tenant相同不足以授权。

## 4. 新增内部API

| 方法/路径 | 提供方→调用方 | 关键合同 |
|---|---|---|
| POST /internal/v1/referral/participants | referral←BFF | 机器身份+用户断言，固定参与版本 |
| POST /internal/v1/referral/invite-tokens | referral←BFF | 同键重放可恢复同token，响应加密留存 |
| POST /internal/v1/referral/invites:resolve | referral←BFF | 公开条款预览，不创建关系 |
| POST /internal/v1/referral/bindings | referral←BFF | token+条款，主体来自断言 |
| GET /internal/v1/referral/me | referral←BFF | 自身进度/奖励/脱敏好友 |
| POST /internal/v1/referral-award-intents | benefit←referral | sourceRequestId+候选签名，新来源CENTER-only |
| GET /internal/v1/referral-award-intents/by-source | benefit←referral | 超时恢复查询，不需先有外部订单号 |
| POST /internal/v1/referral-award-intents:cancel-by-source | benefit←referral | 持久取消栅栏与外部补偿，返回待处理而非伪成功 |
| POST /internal/v1/referral/rewards/{rewardId}:confirm-authorization | referral←benefit | 当前资格revision+stableClaimsHash，返回持久receipt |
| GET /internal/v1/referral/rewards/{rewardId}/authorization | referral←benefit/对账 | 持久授权和取消状态 |
| GET /internal/v1/referral/runtime/proofs/{artifactId} | referral←benefit | 制品及签名发布依据 |
| PUT /internal/v1/referral-runtime/manifest | referral←发布分发器 | 预热且验签，不切流 |
| PUT /internal/v1/referral-runtime/activation | referral←发布分发器 | 仅更高签名sequence生效 |
| POST /internal/v1/award-fulfillment/callbacks | benefit←权益中心 | 仅在HTTP回流方案签收后启用，签名+inbox；Kafka方案不需开放此路由 |

referral runtime同时监听现有签名发布事件并做reconcile，HTTP端点是受信控制面适配入口。发布分发器的source权限和目标cell在部署时明确；内部API不配置到公网edge-gateway。

## 5. 外部API清单（目标合同，不代表外部已有）

| 系统 | 路径 | 状态 |
|---|---|---|
| 会员 | POST /internal/v1/customer-eligibility:query | PROPOSED，规范新客/注册时间及合并 |
| 订单 | GET /internal/v1/orders/{orderId}/referral-evidence | PROPOSED，首单/累计退款快照 |
| 订单 | POST /internal/v1/order-referral-evidence:query | PROPOSED，按主体/期间定位权威首单 |
| 风控 | POST /internal/v1/risk/referral-evaluations | PROPOSED，关联主体/设备及attempt |
| 权益中心 | POST /openapi/v1/award-orders | EXISTING调用路径，新source与SKU版本仍需签收 |
| 权益中心 | GET /openapi/v1/award-orders?sourceSystem=...&sourceRequestId=... | 已核实代码存在；复用查询，补单调版本与规范响应适配 |
| 权益中心 | POST /openapi/v1/award-orders:cancel-by-source | PROPOSED，取消墓碑和已发追回 |

详细字段、错误、SLA与幂等见EXTERNAL_CONTRACTS，不允许在adapter里编造成功响应补齐缺失合同。

## 6. 事件与数据库重点

三个新增Topic：`mk.referral.input.v1`（会员/订单证据）、`mk.referral.fact.v1`（归因/资格/奖励变化）、`mk.award.fulfillment.v1`（最终履约）。existing发布/kill switch事件复用，新的ABI与runtime显式验证。

权威表：subject_role、participant、relation、qualification、reward；热点控制：subject_quota、quota_account/bucket/reservation；可靠性：evidence、inbox/outbox/task、authorization_receipt、compensation；可观察性：audit、measurement贡献/汇总。完整字段/索引/COMMENT规范见DATABASE_DESIGN。

## 7. 开发责任与实施入口

Codex/Claude负责后端领域、API、数据库、合同测试、部署配置和文档；Cursor负责管理端与C端页面。外部会员/订单/风控/权益中心与infra负责人签收各自合同，不假定本仓库可以替他们完成生产变更。

新增模块`services/referral-service`和`runtime-spi/referral-runtime-spi`；逐文件基础任务见FINAL_PLAN第9节，v2新增类/部署任务见PRODUCTION_DESIGN第9节。开始实现前冻结P0参数和合同，随后按P1–P7连续实施并更新进度；不在每个子任务后等待“继续”。
