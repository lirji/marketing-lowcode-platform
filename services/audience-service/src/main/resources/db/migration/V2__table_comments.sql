-- marketing_audience：为已存在的表与字段补充中文注释（Flyway 前向迁移，幂等 ALTER）
-- 说明：本文件仅添加 COMMENT，不改变任何列的类型/可空/默认值。

-- ---- mk_audience_member ----
ALTER TABLE mk_audience_member COMMENT = '受众成员表（快照内的具体成员及命中状态）';
ALTER TABLE mk_audience_member MODIFY COLUMN tenant_id varchar(64) NOT NULL COMMENT '租户ID（多租户隔离键，联合主键首段）';
ALTER TABLE mk_audience_member MODIFY COLUMN snapshot_id varchar(64) NOT NULL COMMENT '所属快照ID';
ALTER TABLE mk_audience_member MODIFY COLUMN subject_hash varchar(64) NOT NULL COMMENT '受众主体哈希（脱敏后的用户标识）';
ALTER TABLE mk_audience_member MODIFY COLUMN membership_version bigint NOT NULL COMMENT '成员版本号';
ALTER TABLE mk_audience_member MODIFY COLUMN active_value tinyint(1) NOT NULL COMMENT '是否有效成员（1=命中，0=移除）';
ALTER TABLE mk_audience_member MODIFY COLUMN updated_at varchar(40) NOT NULL COMMENT '最后更新时间（ISO-8601 字符串，UTC）';

-- ---- mk_audience_snapshot ----
ALTER TABLE mk_audience_snapshot COMMENT = '受众快照表（某时刻分群圈选结果的快照元信息）';
ALTER TABLE mk_audience_snapshot MODIFY COLUMN tenant_id varchar(64) NOT NULL COMMENT '租户ID（多租户隔离键，联合主键首段）';
ALTER TABLE mk_audience_snapshot MODIFY COLUMN snapshot_id varchar(64) NOT NULL COMMENT '快照ID';
ALTER TABLE mk_audience_snapshot MODIFY COLUMN segment_id varchar(128) NOT NULL COMMENT '所属分群ID';
ALTER TABLE mk_audience_snapshot MODIFY COLUMN segment_version bigint NOT NULL COMMENT '分群版本号';
ALTER TABLE mk_audience_snapshot MODIFY COLUMN as_of_time varchar(40) NOT NULL COMMENT '快照基准时间（ISO-8601 字符串，UTC）';
ALTER TABLE mk_audience_snapshot MODIFY COLUMN watermark_time varchar(40) NOT NULL COMMENT '数据水位时间（保证数据完整的截止点）';
ALTER TABLE mk_audience_snapshot MODIFY COLUMN expires_at varchar(40) NOT NULL COMMENT '过期时间（ISO-8601 字符串，UTC）';
ALTER TABLE mk_audience_snapshot MODIFY COLUMN member_count bigint NOT NULL COMMENT '快照成员数量';
ALTER TABLE mk_audience_snapshot MODIFY COLUMN checksum varchar(80) NOT NULL COMMENT '内容校验和';
ALTER TABLE mk_audience_snapshot MODIFY COLUMN state_name varchar(32) NOT NULL COMMENT '状态机当前状态名';
ALTER TABLE mk_audience_snapshot MODIFY COLUMN created_at varchar(40) NOT NULL COMMENT '创建时间（ISO-8601 字符串，UTC）';

-- ---- mk_field_definition ----
ALTER TABLE mk_field_definition COMMENT = '字段定义表（用户画像字段的元数据与治理策略）';
ALTER TABLE mk_field_definition MODIFY COLUMN tenant_id varchar(64) NOT NULL COMMENT '租户ID（多租户隔离键，联合主键首段）';
ALTER TABLE mk_field_definition MODIFY COLUMN field_id varchar(128) NOT NULL COMMENT '字段ID';
ALTER TABLE mk_field_definition MODIFY COLUMN value_type varchar(32) NOT NULL COMMENT '取值类型';
ALTER TABLE mk_field_definition MODIFY COLUMN owner_name varchar(128) NOT NULL COMMENT '字段责任方';
ALTER TABLE mk_field_definition MODIFY COLUMN provenance varchar(1000) NOT NULL COMMENT '数据来源/血缘说明';
ALTER TABLE mk_field_definition MODIFY COLUMN classification varchar(32) NOT NULL COMMENT '数据分级（敏感度）';
ALTER TABLE mk_field_definition MODIFY COLUMN allowed_uses varchar(1000) NOT NULL COMMENT '允许的使用场景列表';
ALTER TABLE mk_field_definition MODIFY COLUMN max_age_seconds bigint NOT NULL COMMENT '数据最大有效期（秒）';
ALTER TABLE mk_field_definition MODIFY COLUMN null_policy varchar(32) NOT NULL COMMENT '空值处理策略';
ALTER TABLE mk_field_definition MODIFY COLUMN missing_policy varchar(32) NOT NULL COMMENT '缺失值处理策略';
ALTER TABLE mk_field_definition MODIFY COLUMN retention_days int NOT NULL COMMENT '保留天数';
ALTER TABLE mk_field_definition MODIFY COLUMN created_at varchar(40) NOT NULL COMMENT '创建时间（ISO-8601 字符串，UTC）';

-- ---- mk_segment_definition ----
ALTER TABLE mk_segment_definition COMMENT = '人群/分群定义表（分群规则的每个版本）';
ALTER TABLE mk_segment_definition MODIFY COLUMN tenant_id varchar(64) NOT NULL COMMENT '租户ID（多租户隔离键，联合主键首段）';
ALTER TABLE mk_segment_definition MODIFY COLUMN segment_id varchar(128) NOT NULL COMMENT '分群ID';
ALTER TABLE mk_segment_definition MODIFY COLUMN version_no bigint NOT NULL COMMENT '版本号（单调递增）';
ALTER TABLE mk_segment_definition MODIFY COLUMN name varchar(256) NOT NULL COMMENT '分群名称';
ALTER TABLE mk_segment_definition MODIFY COLUMN rule_json text NOT NULL COMMENT '分群规则内容（JSON）';
ALTER TABLE mk_segment_definition MODIFY COLUMN rule_hash varchar(80) NOT NULL COMMENT '规则哈希（同一规则去重）';
ALTER TABLE mk_segment_definition MODIFY COLUMN state_name varchar(32) NOT NULL COMMENT '状态机当前状态名';
ALTER TABLE mk_segment_definition MODIFY COLUMN created_by varchar(128) NOT NULL COMMENT '创建人（操作者标识）';
ALTER TABLE mk_segment_definition MODIFY COLUMN created_at varchar(40) NOT NULL COMMENT '创建时间（ISO-8601 字符串，UTC）';
