-- marketing_decision：为已存在的表与字段补充中文注释（Flyway 前向迁移，幂等 ALTER）
-- 说明：本文件仅添加 COMMENT，不改变任何列的类型/可空/默认值。

-- ---- mk_audience_membership_projection ----
ALTER TABLE mk_audience_membership_projection COMMENT = '受众归属投影表（决策侧本地缓存的成员命中结果）';
ALTER TABLE mk_audience_membership_projection MODIFY COLUMN tenant_id varchar(64) NOT NULL COMMENT '租户ID（多租户隔离键，联合主键首段）';
ALTER TABLE mk_audience_membership_projection MODIFY COLUMN audience_id varchar(128) NOT NULL COMMENT '受众/分群ID';
ALTER TABLE mk_audience_membership_projection MODIFY COLUMN subject_hash varchar(64) NOT NULL COMMENT '受众主体哈希（脱敏后的用户标识）';
ALTER TABLE mk_audience_membership_projection MODIFY COLUMN member_value tinyint(1) NOT NULL COMMENT '是否命中（1=命中，0=未命中）';
ALTER TABLE mk_audience_membership_projection MODIFY COLUMN membership_version bigint NOT NULL COMMENT '成员版本号';
ALTER TABLE mk_audience_membership_projection MODIFY COLUMN expires_at varchar(40) NOT NULL COMMENT '过期时间（ISO-8601 字符串，UTC）';
ALTER TABLE mk_audience_membership_projection MODIFY COLUMN updated_at varchar(40) NOT NULL COMMENT '最后更新时间（ISO-8601 字符串，UTC）';

-- ---- mk_decision_command ----
ALTER TABLE mk_decision_command COMMENT = '决策命令幂等表（按幂等键去重与结果重放）';
ALTER TABLE mk_decision_command MODIFY COLUMN tenant_id varchar(64) NOT NULL COMMENT '租户ID（多租户隔离键，联合主键首段）';
ALTER TABLE mk_decision_command MODIFY COLUMN idempotency_key varchar(128) NOT NULL COMMENT '幂等键';
ALTER TABLE mk_decision_command MODIFY COLUMN payload_hash varchar(64) NOT NULL COMMENT '负载哈希（幂等/去重用）';
ALTER TABLE mk_decision_command MODIFY COLUMN state_name varchar(32) NOT NULL COMMENT '状态机当前状态名';
ALTER TABLE mk_decision_command MODIFY COLUMN response_json mediumtext NULL COMMENT '命令处理响应结果（JSON，用于幂等重放返回）';
ALTER TABLE mk_decision_command MODIFY COLUMN created_at varchar(40) NOT NULL COMMENT '创建时间（ISO-8601 字符串，UTC）';
ALTER TABLE mk_decision_command MODIFY COLUMN expires_at varchar(40) NOT NULL COMMENT '过期时间（ISO-8601 字符串，UTC）';

-- ---- mk_decision_kill_switch ----
ALTER TABLE mk_decision_kill_switch COMMENT = '决策运行时熔断开关表（按命名空间紧急停用）';
ALTER TABLE mk_decision_kill_switch MODIFY COLUMN tenant_id varchar(64) NOT NULL COMMENT '租户ID（多租户隔离键，联合主键首段）';
ALTER TABLE mk_decision_kill_switch MODIFY COLUMN namespace_name varchar(128) NOT NULL COMMENT '运行命名空间名';
ALTER TABLE mk_decision_kill_switch MODIFY COLUMN switch_sequence bigint NOT NULL COMMENT '开关变更序号（单调递增）';
ALTER TABLE mk_decision_kill_switch MODIFY COLUMN enabled_value tinyint(1) NOT NULL COMMENT '是否启用（1=启用，0=停用）';
ALTER TABLE mk_decision_kill_switch MODIFY COLUMN reason_text varchar(1000) NOT NULL COMMENT '原因说明';
ALTER TABLE mk_decision_kill_switch MODIFY COLUMN directive_signature varchar(1000) NOT NULL COMMENT '指令签名（防篡改校验）';
ALTER TABLE mk_decision_kill_switch MODIFY COLUMN directive_json mediumtext NOT NULL COMMENT '下发指令内容（JSON）';
ALTER TABLE mk_decision_kill_switch MODIFY COLUMN updated_at varchar(40) NOT NULL COMMENT '最后更新时间（ISO-8601 字符串，UTC）';

-- ---- mk_runtime_generation ----
ALTER TABLE mk_runtime_generation COMMENT = '决策运行时代次表（每个代次的清单与制品，供预热激活）';
ALTER TABLE mk_runtime_generation MODIFY COLUMN tenant_id varchar(64) NOT NULL COMMENT '租户ID（多租户隔离键，联合主键首段）';
ALTER TABLE mk_runtime_generation MODIFY COLUMN environment_name varchar(32) NOT NULL COMMENT '环境名（如 dev/staging/prod）';
ALTER TABLE mk_runtime_generation MODIFY COLUMN cell_id varchar(32) NOT NULL COMMENT '单元格（cell）标识，用于分区/隔离部署';
ALTER TABLE mk_runtime_generation MODIFY COLUMN runtime_name varchar(64) NOT NULL COMMENT '运行时名称';
ALTER TABLE mk_runtime_generation MODIFY COLUMN namespace_name varchar(128) NOT NULL COMMENT '运行命名空间名';
ALTER TABLE mk_runtime_generation MODIFY COLUMN generation_no bigint NOT NULL COMMENT '代次号（每次发布递增）';
ALTER TABLE mk_runtime_generation MODIFY COLUMN release_key_id varchar(128) NOT NULL COMMENT '发布签名密钥ID';
ALTER TABLE mk_runtime_generation MODIFY COLUMN manifest_json mediumtext NOT NULL COMMENT '运行时制品清单（JSON）';
ALTER TABLE mk_runtime_generation MODIFY COLUMN artifact_payload mediumblob NOT NULL COMMENT '编译产物二进制内容';
ALTER TABLE mk_runtime_generation MODIFY COLUMN manifest_signature varchar(256) NOT NULL COMMENT '清单签名（防篡改校验）';
ALTER TABLE mk_runtime_generation MODIFY COLUMN warmed_at varchar(40) NOT NULL COMMENT '预热完成时间（ISO-8601 字符串，UTC）';

-- ---- mk_runtime_slot ----
ALTER TABLE mk_runtime_slot COMMENT = '决策运行时槽位表（各环境/单元/命名空间目标激活代次）';
ALTER TABLE mk_runtime_slot MODIFY COLUMN tenant_id varchar(64) NOT NULL COMMENT '租户ID（多租户隔离键，联合主键首段）';
ALTER TABLE mk_runtime_slot MODIFY COLUMN environment_name varchar(32) NOT NULL COMMENT '环境名（如 dev/staging/prod）';
ALTER TABLE mk_runtime_slot MODIFY COLUMN cell_id varchar(32) NOT NULL COMMENT '单元格（cell）标识，用于分区/隔离部署';
ALTER TABLE mk_runtime_slot MODIFY COLUMN runtime_name varchar(64) NOT NULL COMMENT '运行时名称';
ALTER TABLE mk_runtime_slot MODIFY COLUMN namespace_name varchar(128) NOT NULL COMMENT '运行命名空间名';
ALTER TABLE mk_runtime_slot MODIFY COLUMN desired_generation bigint NOT NULL COMMENT '期望代次号（目标要激活的版本）';
ALTER TABLE mk_runtime_slot MODIFY COLUMN activation_sequence bigint NOT NULL COMMENT '激活序号（单调递增，保证顺序激活）';
ALTER TABLE mk_runtime_slot MODIFY COLUMN directive_signature varchar(256) NOT NULL COMMENT '指令签名（防篡改校验）';
ALTER TABLE mk_runtime_slot MODIFY COLUMN directive_json mediumtext NOT NULL COMMENT '下发指令内容（JSON）';
ALTER TABLE mk_runtime_slot MODIFY COLUMN updated_at varchar(40) NOT NULL COMMENT '最后更新时间（ISO-8601 字符串，UTC）';
