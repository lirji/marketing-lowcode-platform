-- marketing_events：为已存在的表与字段补充中文注释（Flyway 前向迁移，幂等 ALTER）
-- 说明：本文件仅添加 COMMENT，不改变任何列的类型/可空/默认值。

-- ---- mk_event_outbox ----
ALTER TABLE mk_event_outbox COMMENT = '事件网关发件箱（校验通过事件的可靠外发，含重试死信）';
ALTER TABLE mk_event_outbox MODIFY COLUMN tenant_id varchar(64) NOT NULL COMMENT '租户ID（多租户隔离键，联合主键首段）';
ALTER TABLE mk_event_outbox MODIFY COLUMN outbox_id varchar(64) NOT NULL COMMENT '发件箱记录ID';
ALTER TABLE mk_event_outbox MODIFY COLUMN receipt_id varchar(64) NOT NULL COMMENT '关联回执ID';
ALTER TABLE mk_event_outbox MODIFY COLUMN event_type varchar(128) NOT NULL COMMENT '事件类型';
ALTER TABLE mk_event_outbox MODIFY COLUMN destination_topic varchar(249) NOT NULL COMMENT '目标消息主题（Kafka topic）';
ALTER TABLE mk_event_outbox MODIFY COLUMN partition_key varchar(256) NOT NULL COMMENT '分区键（保证同键消息顺序）';
ALTER TABLE mk_event_outbox MODIFY COLUMN stream_sequence bigint NOT NULL COMMENT '流内序号（单调递增，保证顺序与去重）';
ALTER TABLE mk_event_outbox MODIFY COLUMN payload_json mediumtext NOT NULL COMMENT '负载内容（JSON）';
ALTER TABLE mk_event_outbox MODIFY COLUMN created_at varchar(40) NOT NULL COMMENT '创建时间（ISO-8601 字符串，UTC）';
ALTER TABLE mk_event_outbox MODIFY COLUMN published_at varchar(40) NULL COMMENT '发布完成时间（NULL 表示尚未发布）';
ALTER TABLE mk_event_outbox MODIFY COLUMN dead_lettered_at varchar(40) NULL COMMENT '进入死信时间（NULL 表示未死信）';
ALTER TABLE mk_event_outbox MODIFY COLUMN publish_attempts int NOT NULL DEFAULT 0 COMMENT '发布尝试次数';
ALTER TABLE mk_event_outbox MODIFY COLUMN next_attempt_at varchar(40) NOT NULL COMMENT '下次重试时间（ISO-8601 字符串，UTC）';
ALTER TABLE mk_event_outbox MODIFY COLUMN last_error varchar(1000) NOT NULL DEFAULT '' COMMENT '最近一次失败错误信息（空串表示无）';

-- ---- mk_event_receipt ----
ALTER TABLE mk_event_receipt COMMENT = '事件回执表（每条入站事件的落库回执与去重）';
ALTER TABLE mk_event_receipt MODIFY COLUMN tenant_id varchar(64) NOT NULL COMMENT '租户ID（多租户隔离键，联合主键首段）';
ALTER TABLE mk_event_receipt MODIFY COLUMN receipt_id varchar(64) NOT NULL COMMENT '回执ID';
ALTER TABLE mk_event_receipt MODIFY COLUMN source_id varchar(128) NOT NULL COMMENT '来源事件源ID';
ALTER TABLE mk_event_receipt MODIFY COLUMN event_id varchar(128) NOT NULL COMMENT '上游事件ID（源侧标识，去重用）';
ALTER TABLE mk_event_receipt MODIFY COLUMN event_type varchar(128) NOT NULL COMMENT '事件类型';
ALTER TABLE mk_event_receipt MODIFY COLUMN business_key varchar(256) NOT NULL COMMENT '业务键（聚合内幂等键）';
ALTER TABLE mk_event_receipt MODIFY COLUMN aggregate_version bigint NULL COMMENT '聚合版本号（可空，用于顺序校验）';
ALTER TABLE mk_event_receipt MODIFY COLUMN status_name varchar(32) NOT NULL COMMENT '状态名';
ALTER TABLE mk_event_receipt MODIFY COLUMN reason_code varchar(64) NOT NULL COMMENT '原因码（枚举）';
ALTER TABLE mk_event_receipt MODIFY COLUMN payload_hash varchar(64) NOT NULL COMMENT '负载哈希（幂等/去重用）';
ALTER TABLE mk_event_receipt MODIFY COLUMN event_json mediumtext NOT NULL COMMENT '原始事件内容（JSON）';
ALTER TABLE mk_event_receipt MODIFY COLUMN occurred_at varchar(40) NOT NULL COMMENT '业务发生时间（ISO-8601 字符串，UTC）';
ALTER TABLE mk_event_receipt MODIFY COLUMN ingested_at varchar(40) NOT NULL COMMENT '写入/摄取时间（ISO-8601 字符串，UTC）';

-- ---- mk_event_sequence ----
ALTER TABLE mk_event_sequence COMMENT = '事件顺序游标表（各业务键已接收的最新聚合版本）';
ALTER TABLE mk_event_sequence MODIFY COLUMN tenant_id varchar(64) NOT NULL COMMENT '租户ID（多租户隔离键，联合主键首段）';
ALTER TABLE mk_event_sequence MODIFY COLUMN source_id varchar(128) NOT NULL COMMENT '来源事件源ID';
ALTER TABLE mk_event_sequence MODIFY COLUMN business_key varchar(256) NOT NULL COMMENT '业务键（聚合内幂等键）';
ALTER TABLE mk_event_sequence MODIFY COLUMN last_version bigint NOT NULL COMMENT '已接收的最新聚合版本号';
ALTER TABLE mk_event_sequence MODIFY COLUMN updated_at varchar(40) NOT NULL COMMENT '最后更新时间（ISO-8601 字符串，UTC）';

-- ---- mk_event_stream_position ----
ALTER TABLE mk_event_stream_position COMMENT = '事件外发流位点表（各主题分区键已外发的最新序号）';
ALTER TABLE mk_event_stream_position MODIFY COLUMN tenant_id varchar(64) NOT NULL COMMENT '租户ID（多租户隔离键，联合主键首段）';
ALTER TABLE mk_event_stream_position MODIFY COLUMN destination_topic varchar(249) NOT NULL COMMENT '目标消息主题（Kafka topic）';
ALTER TABLE mk_event_stream_position MODIFY COLUMN partition_key varchar(256) NOT NULL COMMENT '分区键（保证同键消息顺序）';
ALTER TABLE mk_event_stream_position MODIFY COLUMN last_sequence bigint NOT NULL COMMENT '已处理的最新序号（消费/派发位点）';
ALTER TABLE mk_event_stream_position MODIFY COLUMN updated_at varchar(40) NOT NULL COMMENT '最后更新时间（ISO-8601 字符串，UTC）';

-- ---- mk_quarantine ----
ALTER TABLE mk_quarantine COMMENT = '事件隔离区表（校验失败被隔离的事件，可重放）';
ALTER TABLE mk_quarantine MODIFY COLUMN tenant_id varchar(64) NOT NULL COMMENT '租户ID（多租户隔离键，联合主键首段）';
ALTER TABLE mk_quarantine MODIFY COLUMN quarantine_id varchar(64) NOT NULL COMMENT '隔离记录ID';
ALTER TABLE mk_quarantine MODIFY COLUMN receipt_id varchar(64) NOT NULL COMMENT '关联回执ID';
ALTER TABLE mk_quarantine MODIFY COLUMN reason_code varchar(64) NOT NULL COMMENT '原因码（枚举）';
ALTER TABLE mk_quarantine MODIFY COLUMN event_json mediumtext NOT NULL COMMENT '被隔离的事件内容（JSON）';
ALTER TABLE mk_quarantine MODIFY COLUMN state_name varchar(32) NOT NULL COMMENT '状态机当前状态名';
ALTER TABLE mk_quarantine MODIFY COLUMN created_at varchar(40) NOT NULL COMMENT '创建时间（ISO-8601 字符串，UTC）';
ALTER TABLE mk_quarantine MODIFY COLUMN replayed_at varchar(40) NULL COMMENT '重放时间（NULL 表示未重放）';

-- ---- mk_source_registration ----
ALTER TABLE mk_source_registration COMMENT = '事件源注册表（上游事件源的接入配置与准入策略）';
ALTER TABLE mk_source_registration MODIFY COLUMN tenant_id varchar(64) NOT NULL COMMENT '租户ID（多租户隔离键，联合主键首段）';
ALTER TABLE mk_source_registration MODIFY COLUMN source_id varchar(128) NOT NULL COMMENT '事件源ID';
ALTER TABLE mk_source_registration MODIFY COLUMN source_uri varchar(1000) NOT NULL COMMENT '事件源地址';
ALTER TABLE mk_source_registration MODIFY COLUMN source_uri_hash varchar(64) NOT NULL COMMENT '事件源地址哈希（唯一约束用）';
ALTER TABLE mk_source_registration MODIFY COLUMN allowed_types varchar(2000) NOT NULL COMMENT '允许上报的事件类型列表';
ALTER TABLE mk_source_registration MODIFY COLUMN schema_versions varchar(1000) NOT NULL COMMENT '支持的 schema 版本列表';
ALTER TABLE mk_source_registration MODIFY COLUMN max_lateness_seconds bigint NOT NULL COMMENT '允许的最大乱序/迟到时长（秒）';
ALTER TABLE mk_source_registration MODIFY COLUMN enabled_value tinyint(1) NOT NULL COMMENT '是否启用（1=启用，0=停用）';
ALTER TABLE mk_source_registration MODIFY COLUMN created_at varchar(40) NOT NULL COMMENT '创建时间（ISO-8601 字符串，UTC）';
