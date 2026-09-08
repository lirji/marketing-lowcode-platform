# 跨项目开发就绪核验

日期：2026-09-07。依据：用户授权的本地项目代码；图片端口用于定位，未将“页面正常”当作接口/容量/生产验收。此次为P0代码能力核验，尚未实现裂变业务或启动联调。

## 1. 仓库与职责

所有仓库位于`/Users/liruijun/personal/LLM/`。

| 仓库 | 已核实入口/能力 | 裂变实施职责 |
|---|---|---|
| auth-platform | AuthzController提供/v1/check、check-bulk、lookup等判权；门户/Casdoor集成 | 登录与组织权限、机器身份；不能代替电商会员新客/首单证据 |
| marketing-lowcode-platform | 已核验控制面、compiler、event、benefit、journey等 | 裂变关系/资格/规则/配额/授权主域；新增referral模块 |
| benefit-center | AwardOrderController提交、按订单号和来源查询；WalletEntryCommandController；RemediationController | 真实发券、券状态、补偿；扩SKU版本约束、按来源取消栅栏等缺口 |
| workflow-platform | ProcessController查询流程；WorkflowStartListener和StartProcessCommandV1启动合同 | 异常人工审核/补偿审批及流程轨迹；不串入每次高QPS绑定事务 |
| risk-platform | POST /api/v1/risk/evaluations；RiskRequest包含counterpartyAccount/deviceId/ip | 复用决策设施，新增裂变场景与关联身份/案件复评合同 |
| recon-platform | 已消费benefit.fulfillment-event.v1和marketing.award-expected.v1；BenefitRemediationController | 复用应发/实发ODS核对与补偿建议，扩marketing-referral来源和关联键 |
| drools-demo | activity-console的/activity-awards/v1/intents等既有活动入口 | 保留既有活动来源兼容；新裂变由marketing规则编译负责，不双写同一奖 |
| dev-infra | 目录存在，本次git status提示不是Git工作树 | 数据库/topic/监控配置的实际部署目录需继续定位；不假设可以在此提交Git变更 |

Grafana为监控入口，仪表盘/告警配置应定位其实际provisioning目录后修改，不将图片地址视为一个已找到的代码仓库。

## 2. 已纠正的设计假设

### 权益订单按来源查询已有

真实路径：GET `/openapi/v1/award-orders?sourceSystem=...&sourceRequestId=...`。

证据：`benefit-center/benefit-server/src/main/java/com/lrj/benefit/web/AwardOrderController.java`的findBySource。返回订单status与items，item包含skuVersion/walletEntryId；没有目标合同中统一的providerRevision字段。adapter应映射真实DTO，不新增重复的`/by-source`别名。

### 履约事件与补偿已有基础

`recon-platform/recon-batch/src/main/resources/application.yml`与`.../ods/BenefitOdsConsumer.java`已引用`benefit.fulfillment-event.v1`；应先核验原事件Schema并复用，再决定营销规范化投影，不盲目要求外部另造回调。

`benefit-center/.../web/RemediationController.java`已有POST `/internal/v1/remediations`、POST `/{remediationNo}/execute`和查询。RemediationCommand要求awardItemNo，支持动作见RemediationAction。该能力不能直接等同“外部订单尚不存在时，按sourceRequestId取消并阻断迟到创建”，后者仍需补取消栅栏协议。

### SKU响应有版本，提交请求未显式锁版本

`benefit-contract/.../AwardItemIntent.java`只有benefitSkuId、type、金额/币种、quantity和metadata，无显式expectedSkuVersion。quantity已经强制1，与本方案原子奖励一致。版本冻结需要扩请求合同及权威接收校验，不能只在营销trace里写版本。

### 风控已有设备字段，尚无裂变业务类型

`risk-platform/fraud-gateway/.../dto/RiskRequest.java`已有交易对手、deviceId/ip；bizType仅TRANSFER/REMITTANCE/PAYMENT/WITHDRAWAL且要求正金额。新券邀请场景不应伪装成转账或伪造金额；应新增版本化场景合同并复用现有决策核心。

### 审批和对账复用边界

workflow启动合同、recon补偿建议可承接人工闭环；运营审批结果不能直接成为任意发奖授权。referral保留资格/配额真值，benefit保留实发真值，recon保存核对与差异，避免三个系统都成为发奖状态主库。

## 3. 开发基线和缺失信息

已授权：上述项目可因裂变任务进行必要后端修改；前端按用户既定规则交给Cursor。

工作树：marketing存在用户/其他工具的前端变更：CampaignsPage.tsx、scopeChoices.ts和scopeChoices.test.ts，予以保留。其余所查Git项目本次status为空；后续修改前再次检查。risk AGENTS.md为前端设计规则，本次后端核验不触发页面设计。

图片中没有明确的电商会员/商城订单项目。Casdoor账号并不天然等于业务新客；权益award-order不是消费首单；风险交易请求也不是订单权威账本。新客、注册、首单、累计退款的真实来源及C端BFF需要用户指定，或明确当前没有此系统后确定可实施范围。不能扫描其他不相关项目找到一个order-service就擅自接入。

可开始的独立工作：referral规则/数据库基础、能力合同、现有权益/风控适配详细核验、注释门禁与领域并发测试。真实注册/首单端到端开发需要上述事实源，生产容量/多AZ仍需指定环境并验证。

## 4. 推荐实施顺序

1. 冻结来源与身份合同；把本次实际接口纳入契约fixture。
2. marketing建立referral领域、数据库、规则编译/发布和注释测试。
3. benefit补SKU版本约束、取消栅栏，marketing接既有查询/履约事件。
4. risk扩裂变场景；referral加入发前评估、授权receipt与补偿。
5. recon/workflow扩差异与人工闭环，复用现有受控动作。
6. Cursor按合同完成管理端/C端；最后联调、容量、故障验收。

本次未执行数据库DDL、未修改业务代码、未发起外部业务请求、未验证图片中的服务运行健康。P0核验通过的内容仅限本文列出的代码事实。
