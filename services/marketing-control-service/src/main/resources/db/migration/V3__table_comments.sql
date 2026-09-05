-- marketing_control：为已存在的表与字段补充中文注释（Flyway 前向迁移，幂等 ALTER）
-- 说明：本文件仅添加 COMMENT，不改变任何列的类型/可空/默认值。

-- ---- mk_activation_directive ----
ALTER TABLE mk_activation_directive COMMENT = '激活指令表（下发给运行时的代次激活指令）';
ALTER TABLE mk_activation_directive MODIFY COLUMN tenant_id varchar(64) NOT NULL COMMENT '租户ID（多租户隔离键，联合主键首段）';
ALTER TABLE mk_activation_directive MODIFY COLUMN directive_id varchar(64) NOT NULL COMMENT '激活指令ID';
ALTER TABLE mk_activation_directive MODIFY COLUMN environment_name varchar(32) NOT NULL COMMENT '环境名（如 dev/staging/prod）';
ALTER TABLE mk_activation_directive MODIFY COLUMN cell_id varchar(32) NOT NULL COMMENT '单元格（cell）标识，用于分区/隔离部署';
ALTER TABLE mk_activation_directive MODIFY COLUMN runtime_name varchar(64) NOT NULL COMMENT '运行时名称';
ALTER TABLE mk_activation_directive MODIFY COLUMN namespace_name varchar(128) NOT NULL COMMENT '运行命名空间名';
ALTER TABLE mk_activation_directive MODIFY COLUMN activation_sequence bigint NOT NULL COMMENT '激活序号（单调递增，保证顺序激活）';
ALTER TABLE mk_activation_directive MODIFY COLUMN generation_no bigint NOT NULL COMMENT '代次号（每次发布递增）';
ALTER TABLE mk_activation_directive MODIFY COLUMN directive_json mediumtext NOT NULL COMMENT '下发指令内容（JSON）';
ALTER TABLE mk_activation_directive MODIFY COLUMN created_at varchar(40) NOT NULL COMMENT '创建时间（ISO-8601 字符串，UTC）';

-- ---- mk_approval_case ----
ALTER TABLE mk_approval_case COMMENT = '审批单表（活动定义版本的审批流程实例）';
ALTER TABLE mk_approval_case MODIFY COLUMN tenant_id varchar(64) NOT NULL COMMENT '租户ID（多租户隔离键，联合主键首段）';
ALTER TABLE mk_approval_case MODIFY COLUMN case_id varchar(64) NOT NULL COMMENT '审批单ID';
ALTER TABLE mk_approval_case MODIFY COLUMN definition_id varchar(128) NOT NULL COMMENT '定义ID';
ALTER TABLE mk_approval_case MODIFY COLUMN definition_version bigint NOT NULL COMMENT '定义版本号';
ALTER TABLE mk_approval_case MODIFY COLUMN submitted_by varchar(128) NOT NULL COMMENT '提交人';
ALTER TABLE mk_approval_case MODIFY COLUMN required_roles varchar(256) NOT NULL COMMENT '所需审批角色列表（逗号分隔）';
ALTER TABLE mk_approval_case MODIFY COLUMN status varchar(32) NOT NULL COMMENT '状态';
ALTER TABLE mk_approval_case MODIFY COLUMN created_at varchar(40) NOT NULL COMMENT '创建时间（ISO-8601 字符串，UTC）';
ALTER TABLE mk_approval_case MODIFY COLUMN updated_at varchar(40) NOT NULL COMMENT '最后更新时间（ISO-8601 字符串，UTC）';

-- ---- mk_approval_decision ----
ALTER TABLE mk_approval_decision COMMENT = '审批决策明细表（各角色对审批单的表决记录）';
ALTER TABLE mk_approval_decision MODIFY COLUMN tenant_id varchar(64) NOT NULL COMMENT '租户ID（多租户隔离键，联合主键首段）';
ALTER TABLE mk_approval_decision MODIFY COLUMN case_id varchar(64) NOT NULL COMMENT '所属审批单ID';
ALTER TABLE mk_approval_decision MODIFY COLUMN role_name varchar(32) NOT NULL COMMENT '表决角色名';
ALTER TABLE mk_approval_decision MODIFY COLUMN actor_id varchar(128) NOT NULL COMMENT '表决人标识';
ALTER TABLE mk_approval_decision MODIFY COLUMN decided_at varchar(40) NOT NULL COMMENT '表决时间（ISO-8601 字符串，UTC）';

-- ---- mk_audit ----
ALTER TABLE mk_audit COMMENT = '审计链表（不可篡改的哈希链审计日志）';
ALTER TABLE mk_audit MODIFY COLUMN sequence_no bigint NOT NULL AUTO_INCREMENT COMMENT '全局自增序号（自增主键，全局顺序）';
ALTER TABLE mk_audit MODIFY COLUMN tenant_id varchar(64) NOT NULL COMMENT '租户ID（多租户隔离键，联合主键首段）';
ALTER TABLE mk_audit MODIFY COLUMN audit_id varchar(64) NOT NULL COMMENT '审计记录ID';
ALTER TABLE mk_audit MODIFY COLUMN chain_index bigint NOT NULL COMMENT '租户内链索引（从 0 递增）';
ALTER TABLE mk_audit MODIFY COLUMN actor_id varchar(128) NOT NULL COMMENT '操作者标识';
ALTER TABLE mk_audit MODIFY COLUMN action_name varchar(64) NOT NULL COMMENT '操作动作名';
ALTER TABLE mk_audit MODIFY COLUMN resource_ref varchar(256) NOT NULL COMMENT '操作对象引用';
ALTER TABLE mk_audit MODIFY COLUMN occurred_at varchar(40) NOT NULL COMMENT '业务发生时间（ISO-8601 字符串，UTC）';
ALTER TABLE mk_audit MODIFY COLUMN previous_hash varchar(64) NOT NULL COMMENT '前一条审计记录哈希（构成哈希链）';
ALTER TABLE mk_audit MODIFY COLUMN entry_hash varchar(64) NOT NULL COMMENT '本条审计记录哈希';

-- ---- mk_audit_head ----
ALTER TABLE mk_audit_head COMMENT = '审计链头表（每租户审计哈希链的最新指针）';
ALTER TABLE mk_audit_head MODIFY COLUMN tenant_id varchar(64) NOT NULL COMMENT '租户ID（多租户隔离键，联合主键首段）';
ALTER TABLE mk_audit_head MODIFY COLUMN last_chain_index bigint NOT NULL COMMENT '最新链索引';
ALTER TABLE mk_audit_head MODIFY COLUMN last_entry_hash varchar(64) NOT NULL COMMENT '最新记录哈希';
ALTER TABLE mk_audit_head MODIFY COLUMN updated_at varchar(40) NOT NULL COMMENT '最后更新时间（ISO-8601 字符串，UTC）';

-- ---- mk_campaign ----
ALTER TABLE mk_campaign COMMENT = '营销活动主表（活动的基本信息与状态）';
ALTER TABLE mk_campaign MODIFY COLUMN tenant_id varchar(64) NOT NULL COMMENT '租户ID（多租户隔离键，联合主键首段）';
ALTER TABLE mk_campaign MODIFY COLUMN campaign_id varchar(64) NOT NULL COMMENT '活动ID（租户内唯一）';
ALTER TABLE mk_campaign MODIFY COLUMN name varchar(200) NOT NULL COMMENT '活动名称';
ALTER TABLE mk_campaign MODIFY COLUMN objective varchar(1000) NOT NULL COMMENT '活动目标描述';
ALTER TABLE mk_campaign MODIFY COLUMN status varchar(32) NOT NULL COMMENT '状态';
ALTER TABLE mk_campaign MODIFY COLUMN organization_id varchar(64) NULL COMMENT '所属组织ID（可空）';
ALTER TABLE mk_campaign MODIFY COLUMN shop_id varchar(64) NULL COMMENT '所属店铺ID（可空）';
ALTER TABLE mk_campaign MODIFY COLUMN created_at varchar(40) NOT NULL COMMENT '创建时间（ISO-8601 字符串，UTC）';
ALTER TABLE mk_campaign MODIFY COLUMN updated_at varchar(40) NOT NULL COMMENT '最后更新时间（ISO-8601 字符串，UTC）';

-- ---- mk_control_command ----
ALTER TABLE mk_control_command COMMENT = '控制面命令幂等表（按幂等键去重与结果重放）';
ALTER TABLE mk_control_command MODIFY COLUMN tenant_id varchar(64) NOT NULL COMMENT '租户ID（多租户隔离键，联合主键首段）';
ALTER TABLE mk_control_command MODIFY COLUMN operation_name varchar(96) NOT NULL COMMENT '操作名';
ALTER TABLE mk_control_command MODIFY COLUMN idempotency_key varchar(128) NOT NULL COMMENT '幂等键';
ALTER TABLE mk_control_command MODIFY COLUMN payload_hash varchar(64) NOT NULL COMMENT '负载哈希（幂等/去重用）';
ALTER TABLE mk_control_command MODIFY COLUMN state_name varchar(32) NOT NULL COMMENT '状态机当前状态名';
ALTER TABLE mk_control_command MODIFY COLUMN response_json text NULL COMMENT '命令处理响应结果（JSON，用于幂等重放返回）';
ALTER TABLE mk_control_command MODIFY COLUMN created_at varchar(40) NOT NULL COMMENT '创建时间（ISO-8601 字符串，UTC）';
ALTER TABLE mk_control_command MODIFY COLUMN expires_at varchar(40) NOT NULL COMMENT '过期时间（ISO-8601 字符串，UTC）';

-- ---- mk_definition_version ----
ALTER TABLE mk_definition_version COMMENT = '活动定义版本表（低代码编排图的每个版本，语义哈希去重）';
ALTER TABLE mk_definition_version MODIFY COLUMN tenant_id varchar(64) NOT NULL COMMENT '租户ID（多租户隔离键，联合主键首段）';
ALTER TABLE mk_definition_version MODIFY COLUMN definition_id varchar(128) NOT NULL COMMENT '定义ID';
ALTER TABLE mk_definition_version MODIFY COLUMN campaign_id varchar(64) NOT NULL COMMENT '所属活动ID';
ALTER TABLE mk_definition_version MODIFY COLUMN version_no bigint NOT NULL COMMENT '版本号（单调递增）';
ALTER TABLE mk_definition_version MODIFY COLUMN dialect varchar(64) NOT NULL COMMENT '定义方言/DSL 类型';
ALTER TABLE mk_definition_version MODIFY COLUMN graph_json text NOT NULL COMMENT '编排图定义内容（JSON）';
ALTER TABLE mk_definition_version MODIFY COLUMN semantic_hash varchar(80) NOT NULL COMMENT '语义哈希（同一定义内容去重）';
ALTER TABLE mk_definition_version MODIFY COLUMN status varchar(32) NOT NULL COMMENT '状态';
ALTER TABLE mk_definition_version MODIFY COLUMN created_by varchar(128) NOT NULL COMMENT '创建人（操作者标识）';
ALTER TABLE mk_definition_version MODIFY COLUMN created_at varchar(40) NOT NULL COMMENT '创建时间（ISO-8601 字符串，UTC）';
ALTER TABLE mk_definition_version MODIFY COLUMN updated_at varchar(40) NOT NULL COMMENT '最后更新时间（ISO-8601 字符串，UTC）';

-- ---- mk_kill_switch ----
ALTER TABLE mk_kill_switch COMMENT = '控制面熔断开关表（按命名空间紧急停用）';
ALTER TABLE mk_kill_switch MODIFY COLUMN tenant_id varchar(64) NOT NULL COMMENT '租户ID（多租户隔离键，联合主键首段）';
ALTER TABLE mk_kill_switch MODIFY COLUMN namespace_name varchar(128) NOT NULL COMMENT '运行命名空间名';
ALTER TABLE mk_kill_switch MODIFY COLUMN switch_sequence bigint NOT NULL COMMENT '开关变更序号（单调递增）';
ALTER TABLE mk_kill_switch MODIFY COLUMN enabled_value tinyint(1) NOT NULL COMMENT '是否启用（1=启用，0=停用）';
ALTER TABLE mk_kill_switch MODIFY COLUMN reason_text varchar(1000) NOT NULL COMMENT '原因说明';
ALTER TABLE mk_kill_switch MODIFY COLUMN updated_by varchar(128) NOT NULL COMMENT '开关更新人';
ALTER TABLE mk_kill_switch MODIFY COLUMN updated_at varchar(40) NOT NULL COMMENT '最后更新时间（ISO-8601 字符串，UTC）';
ALTER TABLE mk_kill_switch MODIFY COLUMN directive_json mediumtext NOT NULL COMMENT '下发指令内容（JSON）';

-- ---- mk_outbox ----
ALTER TABLE mk_outbox COMMENT = '控制面事务发件箱（可靠事件投递，含重试与顺序保证）';
ALTER TABLE mk_outbox MODIFY COLUMN tenant_id varchar(64) NOT NULL COMMENT '租户ID（多租户隔离键，联合主键首段）';
ALTER TABLE mk_outbox MODIFY COLUMN event_id varchar(64) NOT NULL COMMENT '发件箱记录ID（本条待发事件ID）';
ALTER TABLE mk_outbox MODIFY COLUMN aggregate_type varchar(64) NOT NULL COMMENT '聚合根类型';
ALTER TABLE mk_outbox MODIFY COLUMN aggregate_id varchar(128) NOT NULL COMMENT '聚合根ID';
ALTER TABLE mk_outbox MODIFY COLUMN event_type varchar(128) NOT NULL COMMENT '事件类型';
ALTER TABLE mk_outbox MODIFY COLUMN payload_json mediumtext NOT NULL COMMENT '负载内容（JSON）';
ALTER TABLE mk_outbox MODIFY COLUMN occurred_at varchar(40) NOT NULL COMMENT '业务发生时间（ISO-8601 字符串，UTC）';
ALTER TABLE mk_outbox MODIFY COLUMN published_at varchar(40) NULL COMMENT '发布完成时间（NULL 表示尚未发布）';
ALTER TABLE mk_outbox MODIFY COLUMN destination_topic varchar(249) NOT NULL COMMENT '目标消息主题（Kafka topic）';
ALTER TABLE mk_outbox MODIFY COLUMN partition_key varchar(256) NOT NULL COMMENT '分区键（保证同键消息顺序）';
ALTER TABLE mk_outbox MODIFY COLUMN stream_sequence bigint NOT NULL COMMENT '流内序号（单调递增，保证顺序与去重）';
ALTER TABLE mk_outbox MODIFY COLUMN publish_attempts int NOT NULL DEFAULT 0 COMMENT '发布尝试次数';
ALTER TABLE mk_outbox MODIFY COLUMN next_attempt_at varchar(40) NOT NULL COMMENT '下次重试时间（ISO-8601 字符串，UTC）';
ALTER TABLE mk_outbox MODIFY COLUMN last_error varchar(1000) NOT NULL DEFAULT '' COMMENT '最近一次失败错误信息（空串表示无）';

-- ---- mk_release_manifest ----
ALTER TABLE mk_release_manifest COMMENT = '发布清单表（每个发布代次的运行时清单及状态）';
ALTER TABLE mk_release_manifest MODIFY COLUMN tenant_id varchar(64) NOT NULL COMMENT '租户ID（多租户隔离键，联合主键首段）';
ALTER TABLE mk_release_manifest MODIFY COLUMN manifest_id varchar(64) NOT NULL COMMENT '发布清单ID';
ALTER TABLE mk_release_manifest MODIFY COLUMN environment_name varchar(32) NOT NULL COMMENT '环境名（如 dev/staging/prod）';
ALTER TABLE mk_release_manifest MODIFY COLUMN cell_id varchar(32) NOT NULL COMMENT '单元格（cell）标识，用于分区/隔离部署';
ALTER TABLE mk_release_manifest MODIFY COLUMN runtime_name varchar(64) NOT NULL COMMENT '运行时名称';
ALTER TABLE mk_release_manifest MODIFY COLUMN namespace_name varchar(128) NOT NULL COMMENT '运行命名空间名';
ALTER TABLE mk_release_manifest MODIFY COLUMN generation_no bigint NOT NULL COMMENT '代次号（每次发布递增）';
ALTER TABLE mk_release_manifest MODIFY COLUMN state_name varchar(32) NOT NULL COMMENT '状态机当前状态名';
ALTER TABLE mk_release_manifest MODIFY COLUMN manifest_json text NOT NULL COMMENT '运行时制品清单（JSON）';
ALTER TABLE mk_release_manifest MODIFY COLUMN created_at varchar(40) NOT NULL COMMENT '创建时间（ISO-8601 字符串，UTC）';

-- ---- mk_release_slot ----
ALTER TABLE mk_release_slot COMMENT = '发布槽位表（各环境/单元/命名空间的当前发布代次指针）';
ALTER TABLE mk_release_slot MODIFY COLUMN tenant_id varchar(64) NOT NULL COMMENT '租户ID（多租户隔离键，联合主键首段）';
ALTER TABLE mk_release_slot MODIFY COLUMN environment_name varchar(32) NOT NULL COMMENT '环境名（如 dev/staging/prod）';
ALTER TABLE mk_release_slot MODIFY COLUMN cell_id varchar(32) NOT NULL COMMENT '单元格（cell）标识，用于分区/隔离部署';
ALTER TABLE mk_release_slot MODIFY COLUMN runtime_name varchar(64) NOT NULL COMMENT '运行时名称';
ALTER TABLE mk_release_slot MODIFY COLUMN namespace_name varchar(128) NOT NULL COMMENT '运行命名空间名';
ALTER TABLE mk_release_slot MODIFY COLUMN stable_generation bigint NOT NULL COMMENT '稳定代次号（当前稳定运行的版本）';
ALTER TABLE mk_release_slot MODIFY COLUMN desired_generation bigint NOT NULL COMMENT '期望代次号（目标要激活的版本）';
ALTER TABLE mk_release_slot MODIFY COLUMN latest_generation bigint NOT NULL COMMENT '最新代次号（已创建的最大版本）';
ALTER TABLE mk_release_slot MODIFY COLUMN activation_sequence bigint NOT NULL COMMENT '激活序号（单调递增，保证顺序激活）';
ALTER TABLE mk_release_slot MODIFY COLUMN updated_at varchar(40) NOT NULL COMMENT '最后更新时间（ISO-8601 字符串，UTC）';

-- ---- mk_runtime_ack ----
ALTER TABLE mk_runtime_ack COMMENT = '运行时确认表（运行时对发布清单的回执与能力上报）';
ALTER TABLE mk_runtime_ack MODIFY COLUMN tenant_id varchar(64) NOT NULL COMMENT '租户ID（多租户隔离键，联合主键首段）';
ALTER TABLE mk_runtime_ack MODIFY COLUMN manifest_id varchar(64) NOT NULL COMMENT '所属发布清单ID';
ALTER TABLE mk_runtime_ack MODIFY COLUMN runtime_id varchar(128) NOT NULL COMMENT '上报的运行时实例ID';
ALTER TABLE mk_runtime_ack MODIFY COLUMN status_name varchar(32) NOT NULL COMMENT '状态名';
ALTER TABLE mk_runtime_ack MODIFY COLUMN build_digest varchar(128) NOT NULL COMMENT '运行时构建摘要';
ALTER TABLE mk_runtime_ack MODIFY COLUMN supported_abis varchar(1000) NOT NULL COMMENT '支持的 ABI 列表';
ALTER TABLE mk_runtime_ack MODIFY COLUMN warmed_artifact_ids varchar(4000) NOT NULL COMMENT '已预热的制品ID列表';
ALTER TABLE mk_runtime_ack MODIFY COLUMN capacity_value bigint NOT NULL COMMENT '运行时容量值';
ALTER TABLE mk_runtime_ack MODIFY COLUMN acknowledged_at varchar(40) NOT NULL COMMENT '回执时间（ISO-8601 字符串，UTC）';
ALTER TABLE mk_runtime_ack MODIFY COLUMN signature_key_id varchar(128) NOT NULL COMMENT '签名密钥ID';
ALTER TABLE mk_runtime_ack MODIFY COLUMN signature_value varchar(1000) NOT NULL COMMENT '签名值';

-- ---- mk_terms_snapshot ----
ALTER TABLE mk_terms_snapshot COMMENT = '条款快照表（活动定义版本对应的法务条款不可变快照）';
ALTER TABLE mk_terms_snapshot MODIFY COLUMN tenant_id varchar(64) NOT NULL COMMENT '租户ID（多租户隔离键，联合主键首段）';
ALTER TABLE mk_terms_snapshot MODIFY COLUMN terms_id varchar(64) NOT NULL COMMENT '条款快照ID';
ALTER TABLE mk_terms_snapshot MODIFY COLUMN definition_id varchar(128) NOT NULL COMMENT '定义ID';
ALTER TABLE mk_terms_snapshot MODIFY COLUMN definition_version bigint NOT NULL COMMENT '定义版本号';
ALTER TABLE mk_terms_snapshot MODIFY COLUMN content_json text NOT NULL COMMENT '条款内容（JSON）';
ALTER TABLE mk_terms_snapshot MODIFY COLUMN content_hash varchar(80) NOT NULL COMMENT '条款内容哈希';
ALTER TABLE mk_terms_snapshot MODIFY COLUMN created_at varchar(40) NOT NULL COMMENT '创建时间（ISO-8601 字符串，UTC）';
