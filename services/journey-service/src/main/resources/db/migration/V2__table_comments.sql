-- marketing_journey：为已存在的表与字段补充中文注释（Flyway 前向迁移，幂等 ALTER）
-- 说明：本文件仅添加 COMMENT，不改变任何列的类型/可空/默认值。

-- ---- mk_enrollment ----
ALTER TABLE mk_enrollment COMMENT = '旅程参与表（受众进入旅程的实例及运行快照）';
ALTER TABLE mk_enrollment MODIFY COLUMN tenant_id varchar(64) NOT NULL COMMENT '租户ID（多租户隔离键，联合主键首段）';
ALTER TABLE mk_enrollment MODIFY COLUMN enrollment_id varchar(64) NOT NULL COMMENT '参与实例ID';
ALTER TABLE mk_enrollment MODIFY COLUMN journey_id varchar(128) NOT NULL COMMENT '所属旅程ID';
ALTER TABLE mk_enrollment MODIFY COLUMN journey_version bigint NOT NULL COMMENT '旅程版本号';
ALTER TABLE mk_enrollment MODIFY COLUMN subject_token varchar(256) NOT NULL COMMENT '受众主体令牌（脱敏后的用户标识）';
ALTER TABLE mk_enrollment MODIFY COLUMN trigger_event_id varchar(128) NOT NULL COMMENT '触发进入的事件ID';
ALTER TABLE mk_enrollment MODIFY COLUMN status_name varchar(32) NOT NULL COMMENT '状态名';
ALTER TABLE mk_enrollment MODIFY COLUMN current_node_id varchar(128) NOT NULL COMMENT '当前所处节点ID';
ALTER TABLE mk_enrollment MODIFY COLUMN snapshot_json mediumtext NOT NULL COMMENT '参与运行状态快照（JSON）';
ALTER TABLE mk_enrollment MODIFY COLUMN projection_topic varchar(249) NOT NULL DEFAULT '' COMMENT '投影输出主题（空串表示未设置）';
ALTER TABLE mk_enrollment MODIFY COLUMN projection_partition int NOT NULL DEFAULT -1 COMMENT '投影输出分区（-1 表示未设置）';
ALTER TABLE mk_enrollment MODIFY COLUMN projection_offset bigint NOT NULL DEFAULT -1 COMMENT '投影输出位点（-1 表示未设置）';
ALTER TABLE mk_enrollment MODIFY COLUMN created_at varchar(40) NOT NULL COMMENT '创建时间（ISO-8601 字符串，UTC）';
ALTER TABLE mk_enrollment MODIFY COLUMN updated_at varchar(40) NOT NULL COMMENT '最后更新时间（ISO-8601 字符串，UTC）';

-- ---- mk_journey_definition ----
ALTER TABLE mk_journey_definition COMMENT = '旅程定义表（旅程编排计划的每个版本）';
ALTER TABLE mk_journey_definition MODIFY COLUMN tenant_id varchar(64) NOT NULL COMMENT '租户ID（多租户隔离键，联合主键首段）';
ALTER TABLE mk_journey_definition MODIFY COLUMN journey_id varchar(128) NOT NULL COMMENT '旅程ID';
ALTER TABLE mk_journey_definition MODIFY COLUMN version_no bigint NOT NULL COMMENT '版本号（单调递增）';
ALTER TABLE mk_journey_definition MODIFY COLUMN plan_json mediumtext NOT NULL COMMENT '旅程编排计划（JSON）';
ALTER TABLE mk_journey_definition MODIFY COLUMN state_name varchar(32) NOT NULL COMMENT '状态机当前状态名';
ALTER TABLE mk_journey_definition MODIFY COLUMN created_by varchar(128) NOT NULL COMMENT '创建人（操作者标识）';
ALTER TABLE mk_journey_definition MODIFY COLUMN created_at varchar(40) NOT NULL COMMENT '创建时间（ISO-8601 字符串，UTC）';

-- ---- mk_journey_dispatch_outbox ----
ALTER TABLE mk_journey_dispatch_outbox COMMENT = '旅程派发发件箱（节点效果命令的可靠外发，含重试死信）';
ALTER TABLE mk_journey_dispatch_outbox MODIFY COLUMN tenant_id varchar(64) NOT NULL COMMENT '租户ID（多租户隔离键，联合主键首段）';
ALTER TABLE mk_journey_dispatch_outbox MODIFY COLUMN outbox_id varchar(64) NOT NULL COMMENT '发件箱记录ID';
ALTER TABLE mk_journey_dispatch_outbox MODIFY COLUMN command_id varchar(64) NOT NULL COMMENT '关联效果命令ID';
ALTER TABLE mk_journey_dispatch_outbox MODIFY COLUMN enrollment_id varchar(64) NOT NULL COMMENT '旅程参与实例ID（enrollment）';
ALTER TABLE mk_journey_dispatch_outbox MODIFY COLUMN destination_topic varchar(249) NOT NULL COMMENT '目标消息主题（Kafka topic）';
ALTER TABLE mk_journey_dispatch_outbox MODIFY COLUMN partition_key varchar(256) NOT NULL COMMENT '分区键（保证同键消息顺序）';
ALTER TABLE mk_journey_dispatch_outbox MODIFY COLUMN stream_sequence bigint NOT NULL COMMENT '流内序号（单调递增，保证顺序与去重）';
ALTER TABLE mk_journey_dispatch_outbox MODIFY COLUMN payload_json mediumtext NOT NULL COMMENT '负载内容（JSON）';
ALTER TABLE mk_journey_dispatch_outbox MODIFY COLUMN publish_attempts int NOT NULL DEFAULT 0 COMMENT '发布尝试次数';
ALTER TABLE mk_journey_dispatch_outbox MODIFY COLUMN next_attempt_at varchar(40) NOT NULL COMMENT '下次重试时间（ISO-8601 字符串，UTC）';
ALTER TABLE mk_journey_dispatch_outbox MODIFY COLUMN last_error varchar(1000) NOT NULL DEFAULT '' COMMENT '最近一次失败错误信息（空串表示无）';
ALTER TABLE mk_journey_dispatch_outbox MODIFY COLUMN created_at varchar(40) NOT NULL COMMENT '创建时间（ISO-8601 字符串，UTC）';
ALTER TABLE mk_journey_dispatch_outbox MODIFY COLUMN published_at varchar(40) NULL COMMENT '发布完成时间（NULL 表示尚未发布）';
ALTER TABLE mk_journey_dispatch_outbox MODIFY COLUMN dead_lettered_at varchar(40) NULL COMMENT '进入死信时间（NULL 表示未死信）';

-- ---- mk_journey_dispatch_position ----
ALTER TABLE mk_journey_dispatch_position COMMENT = '旅程派发位点表（各参与实例已派发的最新序号）';
ALTER TABLE mk_journey_dispatch_position MODIFY COLUMN tenant_id varchar(64) NOT NULL COMMENT '租户ID（多租户隔离键，联合主键首段）';
ALTER TABLE mk_journey_dispatch_position MODIFY COLUMN enrollment_id varchar(64) NOT NULL COMMENT '旅程参与实例ID（enrollment）';
ALTER TABLE mk_journey_dispatch_position MODIFY COLUMN last_sequence bigint NOT NULL COMMENT '已处理的最新序号（消费/派发位点）';
ALTER TABLE mk_journey_dispatch_position MODIFY COLUMN updated_at varchar(40) NOT NULL COMMENT '最后更新时间（ISO-8601 字符串，UTC）';

-- ---- mk_journey_migration ----
ALTER TABLE mk_journey_migration COMMENT = '旅程迁移表（参与实例在旅程版本间迁移的记录）';
ALTER TABLE mk_journey_migration MODIFY COLUMN tenant_id varchar(64) NOT NULL COMMENT '租户ID（多租户隔离键，联合主键首段）';
ALTER TABLE mk_journey_migration MODIFY COLUMN migration_id varchar(64) NOT NULL COMMENT '迁移记录ID';
ALTER TABLE mk_journey_migration MODIFY COLUMN enrollment_id varchar(64) NOT NULL COMMENT '旅程参与实例ID（enrollment）';
ALTER TABLE mk_journey_migration MODIFY COLUMN from_version bigint NOT NULL COMMENT '迁移前旅程版本号';
ALTER TABLE mk_journey_migration MODIFY COLUMN to_version bigint NOT NULL COMMENT '迁移后旅程版本号';
ALTER TABLE mk_journey_migration MODIFY COLUMN previous_snapshot_json mediumtext NOT NULL COMMENT '迁移前状态快照（JSON）';
ALTER TABLE mk_journey_migration MODIFY COLUMN state_name varchar(32) NOT NULL COMMENT '状态机当前状态名';
ALTER TABLE mk_journey_migration MODIFY COLUMN created_by varchar(128) NOT NULL COMMENT '创建人（操作者标识）';
ALTER TABLE mk_journey_migration MODIFY COLUMN created_at varchar(40) NOT NULL COMMENT '创建时间（ISO-8601 字符串，UTC）';

-- ---- mk_journey_output_receipt ----
ALTER TABLE mk_journey_output_receipt COMMENT = '旅程输出消费回执表（对上游消息按位点去重消费）';
ALTER TABLE mk_journey_output_receipt MODIFY COLUMN source_topic varchar(249) NOT NULL COMMENT '来源消息主题';
ALTER TABLE mk_journey_output_receipt MODIFY COLUMN source_partition int NOT NULL COMMENT '来源消息分区';
ALTER TABLE mk_journey_output_receipt MODIFY COLUMN source_offset bigint NOT NULL COMMENT '来源消息位点 offset';
ALTER TABLE mk_journey_output_receipt MODIFY COLUMN tenant_id varchar(64) NOT NULL COMMENT '租户ID（多租户隔离键，联合主键首段）';
ALTER TABLE mk_journey_output_receipt MODIFY COLUMN enrollment_id varchar(64) NOT NULL COMMENT '旅程参与实例ID（enrollment）';
ALTER TABLE mk_journey_output_receipt MODIFY COLUMN event_type varchar(64) NOT NULL COMMENT '事件类型';
ALTER TABLE mk_journey_output_receipt MODIFY COLUMN payload_hash varchar(64) NOT NULL COMMENT '负载哈希（幂等/去重用）';
ALTER TABLE mk_journey_output_receipt MODIFY COLUMN consumed_at varchar(40) NOT NULL COMMENT '消费时间（ISO-8601 字符串，UTC）';

-- ---- mk_journey_runtime_activation ----
ALTER TABLE mk_journey_runtime_activation COMMENT = '旅程运行时激活记录表（每次代次激活的历史）';
ALTER TABLE mk_journey_runtime_activation MODIFY COLUMN tenant_id varchar(64) NOT NULL COMMENT '租户ID（多租户隔离键，联合主键首段）';
ALTER TABLE mk_journey_runtime_activation MODIFY COLUMN environment_name varchar(32) NOT NULL COMMENT '环境名（如 dev/staging/prod）';
ALTER TABLE mk_journey_runtime_activation MODIFY COLUMN cell_id varchar(32) NOT NULL COMMENT '单元格（cell）标识，用于分区/隔离部署';
ALTER TABLE mk_journey_runtime_activation MODIFY COLUMN namespace_name varchar(128) NOT NULL COMMENT '运行命名空间名';
ALTER TABLE mk_journey_runtime_activation MODIFY COLUMN activation_sequence bigint NOT NULL COMMENT '激活序号（单调递增，保证顺序激活）';
ALTER TABLE mk_journey_runtime_activation MODIFY COLUMN generation_no bigint NOT NULL COMMENT '代次号（每次发布递增）';
ALTER TABLE mk_journey_runtime_activation MODIFY COLUMN directive_signature varchar(1000) NOT NULL COMMENT '指令签名（防篡改校验）';
ALTER TABLE mk_journey_runtime_activation MODIFY COLUMN directive_json mediumtext NOT NULL COMMENT '下发指令内容（JSON）';
ALTER TABLE mk_journey_runtime_activation MODIFY COLUMN activated_at varchar(40) NOT NULL COMMENT '激活时间（ISO-8601 字符串，UTC）';

-- ---- mk_journey_runtime_generation ----
ALTER TABLE mk_journey_runtime_generation COMMENT = '旅程运行时代次表（每个代次的清单与编译产物，供预热激活）';
ALTER TABLE mk_journey_runtime_generation MODIFY COLUMN tenant_id varchar(64) NOT NULL COMMENT '租户ID（多租户隔离键，联合主键首段）';
ALTER TABLE mk_journey_runtime_generation MODIFY COLUMN environment_name varchar(32) NOT NULL COMMENT '环境名（如 dev/staging/prod）';
ALTER TABLE mk_journey_runtime_generation MODIFY COLUMN cell_id varchar(32) NOT NULL COMMENT '单元格（cell）标识，用于分区/隔离部署';
ALTER TABLE mk_journey_runtime_generation MODIFY COLUMN namespace_name varchar(128) NOT NULL COMMENT '运行命名空间名';
ALTER TABLE mk_journey_runtime_generation MODIFY COLUMN generation_no bigint NOT NULL COMMENT '代次号（每次发布递增）';
ALTER TABLE mk_journey_runtime_generation MODIFY COLUMN release_key_id varchar(128) NOT NULL COMMENT '发布签名密钥ID';
ALTER TABLE mk_journey_runtime_generation MODIFY COLUMN manifest_json mediumtext NOT NULL COMMENT '运行时制品清单（JSON）';
ALTER TABLE mk_journey_runtime_generation MODIFY COLUMN artifact_id varchar(160) NOT NULL COMMENT '编译产物ID';
ALTER TABLE mk_journey_runtime_generation MODIFY COLUMN artifact_payload mediumblob NOT NULL COMMENT '编译产物二进制内容';
ALTER TABLE mk_journey_runtime_generation MODIFY COLUMN manifest_signature varchar(1000) NOT NULL COMMENT '清单签名（防篡改校验）';
ALTER TABLE mk_journey_runtime_generation MODIFY COLUMN warmed_at varchar(40) NOT NULL COMMENT '预热完成时间（ISO-8601 字符串，UTC）';

-- ---- mk_journey_runtime_kill_switch ----
ALTER TABLE mk_journey_runtime_kill_switch COMMENT = '旅程运行时熔断开关表（按命名空间紧急停用）';
ALTER TABLE mk_journey_runtime_kill_switch MODIFY COLUMN tenant_id varchar(64) NOT NULL COMMENT '租户ID（多租户隔离键，联合主键首段）';
ALTER TABLE mk_journey_runtime_kill_switch MODIFY COLUMN namespace_name varchar(128) NOT NULL COMMENT '运行命名空间名';
ALTER TABLE mk_journey_runtime_kill_switch MODIFY COLUMN switch_sequence bigint NOT NULL COMMENT '开关变更序号（单调递增）';
ALTER TABLE mk_journey_runtime_kill_switch MODIFY COLUMN enabled_value tinyint(1) NOT NULL COMMENT '是否启用（1=启用，0=停用）';
ALTER TABLE mk_journey_runtime_kill_switch MODIFY COLUMN reason_text varchar(1000) NOT NULL COMMENT '原因说明';
ALTER TABLE mk_journey_runtime_kill_switch MODIFY COLUMN directive_signature varchar(1000) NOT NULL COMMENT '指令签名（防篡改校验）';
ALTER TABLE mk_journey_runtime_kill_switch MODIFY COLUMN directive_json mediumtext NOT NULL COMMENT '下发指令内容（JSON）';
ALTER TABLE mk_journey_runtime_kill_switch MODIFY COLUMN updated_at varchar(40) NOT NULL COMMENT '最后更新时间（ISO-8601 字符串，UTC）';

-- ---- mk_journey_runtime_slot ----
ALTER TABLE mk_journey_runtime_slot COMMENT = '旅程运行时槽位表（各环境/单元/命名空间目标激活代次）';
ALTER TABLE mk_journey_runtime_slot MODIFY COLUMN tenant_id varchar(64) NOT NULL COMMENT '租户ID（多租户隔离键，联合主键首段）';
ALTER TABLE mk_journey_runtime_slot MODIFY COLUMN environment_name varchar(32) NOT NULL COMMENT '环境名（如 dev/staging/prod）';
ALTER TABLE mk_journey_runtime_slot MODIFY COLUMN cell_id varchar(32) NOT NULL COMMENT '单元格（cell）标识，用于分区/隔离部署';
ALTER TABLE mk_journey_runtime_slot MODIFY COLUMN namespace_name varchar(128) NOT NULL COMMENT '运行命名空间名';
ALTER TABLE mk_journey_runtime_slot MODIFY COLUMN desired_generation bigint NOT NULL COMMENT '期望代次号（目标要激活的版本）';
ALTER TABLE mk_journey_runtime_slot MODIFY COLUMN activation_sequence bigint NOT NULL COMMENT '激活序号（单调递增，保证顺序激活）';
ALTER TABLE mk_journey_runtime_slot MODIFY COLUMN directive_signature varchar(1000) NOT NULL COMMENT '指令签名（防篡改校验）';
ALTER TABLE mk_journey_runtime_slot MODIFY COLUMN directive_json mediumtext NOT NULL COMMENT '下发指令内容（JSON）';
ALTER TABLE mk_journey_runtime_slot MODIFY COLUMN updated_at varchar(40) NOT NULL COMMENT '最后更新时间（ISO-8601 字符串，UTC）';

-- ---- mk_journey_timer ----
ALTER TABLE mk_journey_timer COMMENT = '旅程定时器表（等待/延时节点的到期触发计划）';
ALTER TABLE mk_journey_timer MODIFY COLUMN tenant_id varchar(64) NOT NULL COMMENT '租户ID（多租户隔离键，联合主键首段）';
ALTER TABLE mk_journey_timer MODIFY COLUMN timer_key varchar(256) NOT NULL COMMENT '定时器键';
ALTER TABLE mk_journey_timer MODIFY COLUMN enrollment_id varchar(64) NOT NULL COMMENT '旅程参与实例ID（enrollment）';
ALTER TABLE mk_journey_timer MODIFY COLUMN fire_at varchar(40) NOT NULL COMMENT '触发时间（ISO-8601 字符串，UTC）';
ALTER TABLE mk_journey_timer MODIFY COLUMN state_name varchar(32) NOT NULL COMMENT '状态机当前状态名';
ALTER TABLE mk_journey_timer MODIFY COLUMN created_at varchar(40) NOT NULL COMMENT '创建时间（ISO-8601 字符串，UTC）';

-- ---- mk_node_effect_intent ----
ALTER TABLE mk_node_effect_intent COMMENT = '节点效果意图表（旅程节点产生的待执行副作用命令）';
ALTER TABLE mk_node_effect_intent MODIFY COLUMN tenant_id varchar(64) NOT NULL COMMENT '租户ID（多租户隔离键，联合主键首段）';
ALTER TABLE mk_node_effect_intent MODIFY COLUMN command_id varchar(64) NOT NULL COMMENT '效果命令ID（幂等键）';
ALTER TABLE mk_node_effect_intent MODIFY COLUMN enrollment_id varchar(64) NOT NULL COMMENT '旅程参与实例ID（enrollment）';
ALTER TABLE mk_node_effect_intent MODIFY COLUMN node_id varchar(128) NOT NULL COMMENT '产生效果的节点ID';
ALTER TABLE mk_node_effect_intent MODIFY COLUMN effect_type varchar(32) NOT NULL COMMENT '效果/动作类型（如发放权益、发送触达）';
ALTER TABLE mk_node_effect_intent MODIFY COLUMN payload_json mediumtext NOT NULL COMMENT '负载内容（JSON）';
ALTER TABLE mk_node_effect_intent MODIFY COLUMN state_name varchar(32) NOT NULL COMMENT '状态机当前状态名';
ALTER TABLE mk_node_effect_intent MODIFY COLUMN created_at varchar(40) NOT NULL COMMENT '创建时间（ISO-8601 字符串，UTC）';
