# 数据库详细设计与注释交付规范 v2

状态：逻辑与物理字段设计，待实现为Flyway迁移并在真实MySQL验证；本文件不会创建数据库。与FINAL_PLAN不同处以本文和PRODUCTION_DESIGN为准。

## 1. 数据库、命名、注释

新增逻辑库`marketing_referral`，生产按cell建立物理库/账号映射，例如`marketing_referral_cell01`；其余control/benefit/measurement使用各自已有数据库，新增表不放进referral库。MySQL库本身没有与表相同的原生COMMENT槽位：数据库用途、owner、cell、敏感等级、保留期写入基础设施数据库目录和本库`mk_schema_metadata`；所有表和列必须有中文COMMENT。

迁移账号有DDL权限，应用账号仅本库所需DML；运行时不能跨服务join或跨库更新。所有新列写注释，包括旧表ALTER新增列；没有表注释或任一字段注释即阻断验收。注释中不放密码、密钥或个人信息。

通用类型约定：

| 简写 | SQL类型 | 约束/注释模板 |
|---|---|---|
| T | varchar(64) CHARACTER SET ascii COLLATE ascii_bin | tenant_id，非空；“租户ID，所有业务唯一性与访问隔离的首键” |
| ID | varchar(64) CHARACTER SET ascii COLLATE ascii_bin | 实体ID非空；各列COMMENT写具体实体，不用统一“ID”敷衍 |
| REF | varchar(128) CHARACTER SET ascii COLLATE ascii_bin | 外部/定义引用，非空；验证允许字符 |
| H | char(64) CHARACTER SET ascii COLLATE ascii_bin | SHA256/HMAC十六进制摘要；注释区分内容摘要和主体索引 |
| C | varchar(32) CHARACTER SET ascii COLLATE ascii_bin | 状态/枚举；NOT NULL，检查合法值 |
| N | bigint unsigned | 数量/版本，NOT NULL；金额另为bigint，必须注明最小单位与币种 |
| DT | datetime(6) | UTC时间，精确到微秒；是否可空逐字段指定 |
| J | json | 版本化结构数据；热过滤字段不能仅放JSON |
| SECRET | varbinary(2048) | 加密主体/幂等响应；密钥引用另列，禁止原文索引 |

标`?`可空，其余非空。所有表默认InnoDB，utf8mb4，机器键用ascii_bin；人类文本按业务选排序规则。除inbox等明确列出外，每表都有`tenant_id T, created_at DT, updated_at DT`；可变表有`row_version N DEFAULT 1`。这四列也必须逐列COMMENT。下述字段中文解释就是迁移注释的最低内容，不能在生成SQL时丢失。

ID随机性和长度在入口校验；主体实际值不限定ASCII，先经身份系统规范化，再HMAC得到`subject_key H`作比较/索引，原主体加密存`subject_cipher SECRET`并记`subject_key_version N`。不能用截断前缀唯一索引判断主体唯一。

## 2. Referral数据字典

以下表名、字段、键为实施合同。为避免文档重复，通用列按第1节展开；PK、UK和IX均以tenant开头，库内token也遵循租户边界。合法跨库引用由应用层和对账验证。

### D01 mk_schema_metadata — 数据库用途与治理元数据

字段：`schema_key ID`逻辑数据库标识；`description varchar(500)`数据库业务用途；`owner_team varchar(128)`维护团队；`cell_id ID`所属隔离单元；`data_class C`敏感级别；`retention_policy J`分表保留规则；`schema_version N`设计版本。PK(tenant_id,schema_key)。本表每列及本表自身也有COMMENT；系统元数据使用约定系统租户，不能开放普通业务查询。

### D02 mk_referral_subject_role — 防自邀/互邀的活动主体角色

字段：`campaign_id ID`活动；`subject_key H`规范主体索引；`subject_key_version N`索引密钥版本；`role C`INVITER/INVITEE；`participant_id ID?`邀请人参与记录；`relation_id ID?`被邀请关系。PK(tenant,campaign,subject_key)。首期同活动身份互斥；绑定时对A/B主体按key排序锁定，数据库唯一键处理首次插入竞争。未来允许新客成为邀请人须发布新政策及迁移设计。

### D03 mk_referral_participant — 邀请人参与及固定版本

字段：`participant_id ID`参与ID；`campaign_id ID`活动；`organization_id ID`组织；`shop_id ID`店铺；`subject_key H`邀请人索引；`subject_key_version N`索引版本；`subject_cipher SECRET`加密主体；`encryption_key_id REF`密钥引用；`definition_id REF`规则定义；`definition_version N`固定定义版本；`generation N`固定发布代次；`artifact_id varchar(160)`制品ID；`policy_hash H`规则摘要；`route_epoch N`cell路由代次；`state C`ACTIVE/PAUSED/CLOSED。PK(tenant,participant)；UK(tenant,campaign,subject_key)；IX(tenant,campaign,created_at,participant)。冻结字段不能通过普通更新修改。

### D04 mk_referral_invite_token — 邀请token摘要与撤销

字段：`token_id ID`ID；`token_hash H`256位随机token摘要；`participant_id ID`所属邀请人；`expires_at DT`失效时间；`revoked_at DT?`撤销时间；`token_version N`格式版本。PK(tenant,token_id)；UK(tenant,token_hash)；IX(tenant,participant,expires_at)。不保存明文token；API重放响应加密保存在D20，避免丢响应后无法返回原token。

### D05 mk_referral_relation — 邀请关系与归因真值

字段：`relation_id ID`关系；`campaign_id ID`活动；`participant_id ID`邀请人；`invitee_key H`好友索引；`subject_key_version N`索引版本；`invitee_cipher SECRET`好友主体密文；`encryption_key_id REF`密钥引用；`bound_at DT`首次有效绑定；`deadline_at DT`达标截止；`consent_version REF`接受条款版本；`policy_hash H`冻结规则摘要；`state C`BOUND/PENDING_QUALIFICATION/QUALIFIED/EXPIRED/INVALIDATED/BLOCKED/REVIEW；`reason_code C`稳定原因。PK(tenant,relation)；UK(tenant,campaign,invitee_key)；IX(tenant,participant,state,bound_at,relation)；IX(tenant,invitee_key,campaign)。UK不含definitionVersion。

### D06 mk_referral_evidence — 不可变业务证据

字段：`evidence_id ID`证据；`source_system ID`认证来源；`business_key REF`订单/客户业务键；`subject_key H`关联主体；`fact_type C`事实类型；`aggregate_revision N`上游权威修订；`occurred_at DT`业务发生时间；`payload_hash H`原合同摘要；`normalized_json J`规范化且脱敏事实；`schema_version C`Schema版本；`archive_ref varchar(512)?`归档引用。PK(tenant,evidence)；UK(tenant,source,fact_type,business_key,aggregate_revision)；IX(tenant,subject_key,occurred_at,evidence)。同修订不同摘要进入冲突隔离，不能覆盖。

### D07 mk_referral_qualification — 可修订资格

字段：`qualification_id ID`ID；`relation_id ID`关系；`goal_type C`注册/首单；`order_id REF?`首单；`evidence_id ID`本次证据；`evidence_revision N`已应用证据修订；`net_amount_minor bigint`可计净额最小单位；`currency char(3)`币种；`settle_after DT`观察期到期；`qualified_flag boolean`是否计入有效人数；`state C`PENDING/QUALIFIED/INVALIDATED/EXPIRED/REVIEW；`reason_code C`原因；`risk_decision_id REF?`资格风控引用。PK(tenant,qualification)；UK(tenant,relation,goal_type)；IX(tenant,state,settle_after,qualification)。CHECK净额≥0；状态变更必须匹配revision CAS。

### D08 mk_referral_progress — 当前人数投影（事务内权威计数）

字段：`participant_id ID`聚合根；`valid_count N`当前有效邀请人数；`lifetime_qualified_count N`历史首次达标关系数；`last_revision N`聚合递增版本。PK(tenant,participant)。历史首次计数须根据relation首次达标标记/历史证据计算，不把true→false→true当第二个“首次”；可新增qualification.first_qualified_at DT?实现标记，并为该字段写注释。

### D09 mk_referral_subject_quota — 用户按规则限领

字段：`participant_id ID`邀请聚合；`beneficiary_key H`受益人；`rule_id ID`规则；`quota_limit N`用户上限；`reserved_count N`待发占用；`consumed_count N`成功消耗。PK(tenant,participant,beneficiary_key,rule)。CHECK reserved+consumed≤limit；与reward创建及bucket占用同事务。

### D10 mk_referral_reward — 稳定奖励身份及正交状态

字段：`reward_id ID`奖励；`campaign_id ID`活动；`participant_id ID`聚合根；`relation_id ID?`逐人关系（阶梯为空）；`beneficiary_key H`受益人索引；`beneficiary_cipher SECRET`主体密文；`encryption_key_id REF`密钥；`role C`INVITER/INVITEE；`rule_id ID`稳定规则；`milestone_key ID`逐人relationId或阶梯threshold规范值；`benefit_definition_ref REF`冻结权益定义；`sku_id REF`券SKU；`sku_version N`冻结SKU版本；`quantity N`首期固定1；`entitlement_revision N`资格修订；`entitlement_state/authorization_state/risk_state/delivery_state/compensation_state C`五个维度（枚举见生产设计）；`source_request_id REF`外部稳定幂等键；`award_intent_id ID?`营销发奖意图；`award_order_no REF?`外部订单；`last_error varchar(1000)`脱敏失败信息，默认空。PK(tenant,reward)；UK(tenant,campaign,beneficiary_key,role,rule,milestone)；UK(tenant,source_request_id)；IX(tenant,participant,created_at,reward)；IX(tenant,campaign,delivery_state,updated_at,reward)。CHECK quantity=1；UK所有字段NOT NULL。

### D11 mk_referral_reward_attempt — 编排尝试历史

字段：`attempt_id ID`尝试；`reward_id ID`稳定奖励；`attempt_no N`递增尝试号；`authorization_digest H?`候选凭证摘要；`risk_decision_id REF?`风控；`state C`尝试状态；`next_retry_at DT`下次尝试；`last_error varchar(1000)`错误。PK(tenant,attempt)；UK(tenant,reward,attempt_no)；IX(tenant,state,next_retry_at,attempt)。租约由D18任务表管理，避免两套互相覆盖的owner。

### D12 mk_referral_quota_account — 活动总额度冷路径

字段：`campaign_id ID`活动；`rule_id ID`规则；`quota_limit N`总名额；`allocated_total N`已分配到bucket的额度；`epoch N`调拨版本；`state C`ACTIVE/PAUSED。PK(tenant,campaign,rule)。CHECK allocated_total≤quota_limit。不在每次发奖更新本行。

### D13 mk_referral_quota_bucket — 热路径配额桶

字段：`campaign_id ID`活动；`rule_id ID`规则；`bucket_id int unsigned`桶号；`allocated N`可支配总额度；`available N`空闲；`reserved N`待发；`consumed N`已成功；`fencing_epoch N`旧worker栅栏；`state C`ACTIVE/FROZEN。PK(tenant,campaign,rule,bucket)。CHECK allocated=available+reserved+consumed；各字段非负；配额变更使用epoch+version CAS。

### D14 mk_referral_quota_reservation — 每份奖励配额占用

字段：`reservation_id ID`预占；`reward_id ID`奖励；`campaign_id ID/rule_id ID/bucket_id int unsigned`桶引用；`quantity N`占用1；`state C`RESERVED/CONSUMED/RELEASED；`created_epoch N`预占时桶代次；`expires_at DT?`仅提示复核，不自动释放未知结果；`released_reason C?`释放依据。PK(tenant,reservation)；UK(tenant,reward)；IX(tenant,state,updated_at,reservation)。释放与bucket变化同事务。

### D15 mk_referral_authorization_receipt — 首次授权消费凭证

字段：`reward_id ID`奖励；`source_request_id REF`稳定幂等键；`stable_claims_hash H`不可变声明摘要；`qualification_revision N`首次确认修订；`authorization_sequence N`授权序号；`confirmed_at DT`首次确认；`state C`CONFIRMED/CANCEL_REQUESTED/CLOSED；`cancel_revision N`撤销修订；`receipt_json J`签名/服务端授权回执。PK(tenant,reward)；UK(tenant,source_request_id)；IX(tenant,state,updated_at,reward)。确认后不能因候选token过期删除此记录。

### D16 mk_referral_compensation — 取消和追回过程

字段：`compensation_id ID`任务；`reward_id ID`奖励；`cause_evidence_id ID`退款/撤销依据；`revision N`补偿修订；`state C`PENDING/CANCELLED/REVERSED/MANUAL_REVIEW；`external_reversal_id REF?`外部撤回；`last_error varchar(1000)`原因。PK(tenant,compensation)；UK(tenant,reward,revision)；IX(tenant,state,updated_at,compensation)。执行重试使用D18。

### D17 mk_referral_inbox — 输入事件幂等收据

字段：`source_system ID`认证来源；`event_id REF`事件；`payload_hash H`摘要；`processed_at DT`处理提交时间。PK(tenant,source,event)。只在证据和派生outbox持久化同事务内插入；不存在“已经插inbox但业务没执行”的中间成功态。归档删除须保留覆盖最大重放窗口的去重摘要或禁止超窗口重放。

### D18 mk_referral_task — 观察期/重试/对账持久任务

字段：`task_id ID`任务；`task_type C`SETTLE/RETRY/AUTH_RECONCILE/COMPENSATE；`resource_id ID`目标；`resource_revision N`任务代次；`state C`READY/LEASED/DONE/DEAD；`due_at DT`到期UTC；`lease_owner REF?`执行者；`lease_until DT?`租约期限；`lease_version N`栅栏；`attempts int unsigned`次数；`last_error varchar(1000)`错误。PK(tenant,task)；UK(tenant,type,resource,revision)；IX(tenant,state,due_at,task)；IX(tenant,state,lease_until,task)。扫描按tenant公平调度，持锁领取后尽快提交。

### D19 mk_referral_outbox — 可靠发送任务

字段：`event_id ID`事件；`aggregate_id ID`聚合；`aggregate_revision N`聚合版本；`event_type C`事实类型；`topic REF`目标主题/受控HTTP通道；`partition_key varchar(256)`分区键；`payload J`版本化负载；`payload_hash H`摘要；`state C`READY/LEASED/SENT/DEAD；`attempts int unsigned`次数；`next_retry_at DT`重试；`lease_owner REF? / lease_until DT? / lease_version N`租约；`published_at DT?`成功确认；`last_error varchar(1000)`错误。PK(tenant,event)；UK(tenant,aggregate,event_type,aggregate_revision)；IX(tenant,state,next_retry_at,event)。多个同修订奖励事件使用reward聚合ID或各自递增版本，不能共用participant+revision撞UK。

### D20 mk_referral_api_idempotency — 接口请求幂等

字段：`operation_name REF`接口操作；`subject_key H`身份作用域；`idempotency_key REF`请求键；`request_hash H`规范业务请求摘要；`state C`PROCESSING/COMPLETED；`response_cipher mediumblob?`含token时必须加密的重放响应；`encryption_key_id REF?`密钥；`lease_owner REF? / lease_until DT? / lease_version N`租约；`expires_at DT`保留期限。PK(tenant,operation,subject_key,idempotency_key)。复用平台执行器需增加加密codec和新库adapter，不把敏感response写入现有明文实现；其他服务旧行为不变。24h后可清理接口响应，但关系/reward业务唯一键永久跨重试有效。

### D21 mk_referral_audit — 领域审计

字段：`audit_id ID`审计；`actor_id REF`机器/操作人；`action C`动作；`resource_ref REF`目标；`reason varchar(1000)`原因；`before_hash H? / after_hash H?`状态摘要；`trace_id ID`链路；`occurred_at DT`业务时间。PK(tenant,audit)；IX(tenant,resource_ref,occurred_at,audit)。应用权限只追加，不允许更新历史；按策略归档到受保护存储。

### D22–D24 runtime表 — 发布证明、激活、熔断

- `mk_referral_runtime_generation`：`environment C, cell_id ID, namespace_id REF, generation N, manifest_json J, artifact_id varchar(160), artifact_payload mediumblob, artifact_hash H, signature_key_id REF, installed_at DT`。PK(tenant,environment,cell,namespace,generation)。所有列注释明确是固定发布快照。
- `mk_referral_runtime_slot`：`environment C, cell_id ID, namespace_id REF, active_generation N, activation_sequence N, directive_json J`。PK(tenant,environment,cell,namespace)。激活仅增序号；通过新序号引用旧generation完成回滚。
- `mk_referral_runtime_kill_switch`：`namespace_id REF, switch_sequence N, enabled boolean, reason varchar(1000), directive_json J, refreshed_at DT`。PK(tenant,namespace)。enabled指“允许业务运行”，不是“熔断已开启”，必须在字段注释写清。

## 3. 其他服务数据字典增量

以下沿用通用tenant/时间/version列；既有表时间为varchar时不在本功能中全量改类型，新表UTC DT与旧表通过适配转换。

| 表/归属 | 新增字段及中文含义 | 唯一性/查询索引 |
|---|---|---|
| `mk_campaign` / control修改 | `campaign_type C DEFAULT 'STANDARD'`活动类型 | CHECK STANDARD/REFERRAL；保持旧PK |
| `mk_campaign_primary_definition` / control新增 | campaign_id ID活动、component_type C组件类型、definition_id REF主定义 | PK(tenant,campaign,component_type)，绑定主referral定义；版本仍在已有表 |
| `mk_award_authorization` / benefit新增 | reward_id ID奖励、source_system ID来源、source_request_id REF幂等键、claims_hash H声明、receipt_json J确认凭据、artifact_hash H规则、beneficiary_key H受益人、state C授权态 | PK(tenant,source,reward)；UK(tenant,source,source_request) |
| `mk_referral_award_evaluation` / benefit新增 | reward_id ID、attempt_no N尝试、claims_hash H、risk_decision_id REF?、risk_revision N、risk_action C、case_ref REF?、lease_owner REF?、lease_until DT?、lease_version N、next_retry_at DT、reason varchar(1000) | PK(tenant,reward,attempt)；IX(tenant,risk_action,next_retry_at,reward) |
| `mk_award_fulfillment` / benefit新增 | source_system ID、source_request_id REF、intent_id ID、award_order_no REF?、item_id ID、delivery_state C、provider_revision N、delivered_at DT?、compensation_state C、reversal_revision N | PK(tenant,source,source_request,item)；IX(tenant,delivery_state,updated_at) |
| `mk_award_result_inbox` / benefit新增 | provider_id ID供应商、event_id REF事件、payload_hash H、provider_revision N | PK(tenant,provider,event)；业务去重另由fulfillment版本实现 |
| `mk_award_compensation` / benefit新增 | reward_id ID、compensation_revision N、source_system ID、source_request_id REF、state C、external_reversal_id REF?、next_retry_at DT、lease_owner REF?、lease_until DT?、lease_version N、reason varchar(1000) | PK(tenant,reward,revision)；IX(tenant,state,next_retry_at,reward) |
| `mk_award_cancel_fence` / benefit新增 | source_system ID、source_request_id REF、cancel_revision N、reason varchar(1000)、external_fence_confirmed boolean外部迟到创建已被阻断 | PK(tenant,source,source_request)；没有外部确认不能据此释放已投递未知配额 |
| `mk_referral_metric_contribution` / measurement新增 | event_id REF、aggregate_id ID、aggregate_revision N、campaign_id ID、metric_type C、value bigint有符号贡献、bucket_at DT时间桶 | PK(tenant,event)；UK(tenant,aggregate,metric_type,revision)，处理修订反转 |
| `mk_referral_metric_bucket` / measurement新增 | campaign_id ID、bucket_at DT、metric_type C、value bigint汇总、watermark_at DT | PK(tenant,campaign,bucket_at,metric_type) |
| `mk_event_source_principal` / event gateway新增 | source_id ID、principal_id REF可信机器主体、fact_type C、enabled boolean | PK(tenant,source,principal,fact_type)；接入源不能仅凭客户端自报sourceId |

每个服务自己的result outbox、API幂等/任务机制优先复用原实现；如需新增表，同样展开通用字段并写COMMENT，禁止以“内部表”为例外。

## 4. 带完整注释的DDL示例与关键SQL

以下为D13完整示例，其他表必须由字典生成同等完整的迁移。此处不代表全套Flyway已实现或执行。

```sql
-- 活动总额预分到桶；发奖只更新命中的桶，避免活动总账户成为高并发热行。
CREATE TABLE mk_referral_quota_bucket (
  tenant_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '租户ID，隔离边界与联合主键首列',
  campaign_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '活动ID，同一活动固定权威cell',
  rule_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '稳定奖励规则ID，跨定义版本不复用为其他语义',
  bucket_id int unsigned NOT NULL COMMENT '活动奖励规则下的配额桶编号',
  allocated bigint unsigned NOT NULL COMMENT '本桶被分配的总发券名额，单位张',
  available bigint unsigned NOT NULL COMMENT '未占用名额，单位张，允许冷路径调拨',
  reserved bigint unsigned NOT NULL DEFAULT 0 COMMENT '已预占但尚未明确终态的名额，单位张',
  consumed bigint unsigned NOT NULL DEFAULT 0 COMMENT '已成功履约且未按政策返还的名额，单位张',
  fencing_epoch bigint unsigned NOT NULL DEFAULT 1 COMMENT '调拨和冻结时递增的栅栏代次，阻断旧worker写入',
  state varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'ACTIVE' COMMENT '运行状态：ACTIVE允许预占，FROZEN禁止新预占',
  row_version bigint unsigned NOT NULL DEFAULT 1 COMMENT '乐观锁版本，每次变更递增',
  created_at datetime(6) NOT NULL COMMENT '创建时间，UTC，微秒精度',
  updated_at datetime(6) NOT NULL COMMENT '最后变更时间，UTC，微秒精度',
  PRIMARY KEY (tenant_id, campaign_id, rule_id, bucket_id),
  CONSTRAINT ck_referral_bucket_balance CHECK (allocated = available + reserved + consumed),
  CONSTRAINT ck_referral_bucket_state CHECK (state IN ('ACTIVE','FROZEN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='裂变活动发券配额桶，数据库权威预占及守恒账本';
```

预占SQL（MyBatis占位符，仅作为设计示例）：

```sql
-- 必须与用户限领、唯一reward/reservation及outbox同事务；失败返回等待配额或冲突，不丢资格。
UPDATE mk_referral_quota_bucket
SET available = available - 1, reserved = reserved + 1,
    row_version = row_version + 1, updated_at = UTC_TIMESTAMP(6)
WHERE tenant_id = #{tenantId} AND campaign_id = #{campaignId}
  AND rule_id = #{ruleId} AND bucket_id = #{bucketId}
  AND state = 'ACTIVE' AND available >= 1
  AND fencing_epoch = #{expectedEpoch} AND row_version = #{expectedVersion};
```

幂等事务需要区分“唯一键已存在同内容”与“键相同不同内容”；禁止INSERT IGNORE吞掉所有数据错误。必要的死锁重试在事务外重跑整个幂等命令，最大3次带抖动，超限交还持久任务；测试验证不会形成重试风暴。

## 5. 索引、容量、分片与归档

最宽复合唯一键采用H摘要和ASCII机器ID，将估算字节数写入评审记录；真实MySQL创建验证最大索引长度，不能只靠字符数推断。所有列表WHERE前缀匹配tenant+campaign/participant，按created_at+id seek；禁止大OFFSET扫描。

所有关键SQL提供`EXPLAIN ANALYZE`（仅在有授权的测试数据环境）和锁等待报告：绑定唯一键、按主体找关系、due任务领取、outbox领取、reward列表、bucket预占。单次扫描批量≤100，SQL参数列表≤100；避免按每条证据逐个远程查询造成N+1。

首期每campaign一个cell单库，应用内租户隔离；历史证据按时间归档到对象存储，关系/奖励保留可查询索引。不能仅按日期分区关系表而破坏跨时间归因唯一性；大规模分表需把所有唯一键约束纳入路由设计再评审。

## 6. 迁移与注释验收

新表先部署，旧表只做向后兼容新增列；旧活动回填STANDARD分批执行，保留进度。不可在线原地修改已有Flyway版本；实施时按最新序号新增。新方言在所有读者升级前不开启，避免旧服务读到未知enum。

CI建立临时MySQL，执行本次所有服务迁移，再运行以下断言（schema过滤到本次变更清单）：

```sql
-- 返回任何行即失败：表注释缺失。
SELECT table_schema, table_name FROM information_schema.tables
WHERE table_schema = 'marketing_referral' AND table_type = 'BASE TABLE'
  AND TRIM(table_comment) = '';
-- 返回任何行即失败：字段注释缺失，包括主键、时间列、内部表字段。
SELECT table_schema, table_name, column_name FROM information_schema.columns
WHERE table_schema = 'marketing_referral' AND TRIM(column_comment) = '';
```

其他服务按本次新增表/列清单做同样检查；CI还检查NOT NULL唯一键、合法状态CHECK、数据库时区、迁移重复运行无新增副作用。Javadoc/SQL注释不能使用TODO作为合格说明。

恢复验收：备份恢复→唯一键核验→bucket守恒→授权与reward一致→外部订单对账→再开放写入。任何迁移脚本、数据清理和生产DDL均在后续明确授权的实施流程执行，本次未执行。
