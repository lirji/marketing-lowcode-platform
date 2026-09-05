-- marketing_engagement：为已存在的表与字段补充中文注释（Flyway 前向迁移，幂等 ALTER）
-- 说明：本文件仅添加 COMMENT，不改变任何列的类型/可空/默认值。

-- ---- mk_consent ----
ALTER TABLE mk_consent COMMENT = '同意授权表（用户各渠道的营销同意与个性化授权）';
ALTER TABLE mk_consent MODIFY COLUMN tenant_id varchar(64) NOT NULL COMMENT '租户ID（多租户隔离键，联合主键首段）';
ALTER TABLE mk_consent MODIFY COLUMN subject_token varchar(256) NOT NULL COMMENT '受众主体令牌（脱敏后的用户标识）';
ALTER TABLE mk_consent MODIFY COLUMN channel_name varchar(32) NOT NULL COMMENT '触达渠道名（如 sms/email/push）';
ALTER TABLE mk_consent MODIFY COLUMN allowed_value tinyint(1) NOT NULL COMMENT '是否允许触达（1=允许，0=拒绝）';
ALTER TABLE mk_consent MODIFY COLUMN minor_value tinyint(1) NOT NULL COMMENT '是否未成年人（1=是，0=否）';
ALTER TABLE mk_consent MODIFY COLUMN personalization_allowed tinyint(1) NOT NULL COMMENT '是否允许个性化（1=允许，0=拒绝）';
ALTER TABLE mk_consent MODIFY COLUMN version_no bigint NOT NULL COMMENT '版本号（单调递增）';
ALTER TABLE mk_consent MODIFY COLUMN source_name varchar(128) NOT NULL COMMENT '授权来源';
ALTER TABLE mk_consent MODIFY COLUMN effective_at varchar(40) NOT NULL COMMENT '生效时间（ISO-8601 字符串，UTC）';
ALTER TABLE mk_consent MODIFY COLUMN updated_at varchar(40) NOT NULL COMMENT '最后更新时间（ISO-8601 字符串，UTC）';

-- ---- mk_contact_attempt ----
ALTER TABLE mk_contact_attempt COMMENT = '触达尝试表（一次具体触达的请求与投递状态）';
ALTER TABLE mk_contact_attempt MODIFY COLUMN tenant_id varchar(64) NOT NULL COMMENT '租户ID（多租户隔离键，联合主键首段）';
ALTER TABLE mk_contact_attempt MODIFY COLUMN contact_id varchar(64) NOT NULL COMMENT '触达实例ID';
ALTER TABLE mk_contact_attempt MODIFY COLUMN contact_key varchar(128) NOT NULL COMMENT '触达幂等键';
ALTER TABLE mk_contact_attempt MODIFY COLUMN subject_token varchar(256) NOT NULL COMMENT '受众主体令牌（脱敏后的用户标识）';
ALTER TABLE mk_contact_attempt MODIFY COLUMN campaign_id varchar(128) NOT NULL COMMENT '营销活动ID';
ALTER TABLE mk_contact_attempt MODIFY COLUMN channel_name varchar(32) NOT NULL COMMENT '触达渠道名（如 sms/email/push）';
ALTER TABLE mk_contact_attempt MODIFY COLUMN template_id varchar(128) NOT NULL COMMENT '使用的模板ID';
ALTER TABLE mk_contact_attempt MODIFY COLUMN template_version bigint NOT NULL COMMENT '模板版本号';
ALTER TABLE mk_contact_attempt MODIFY COLUMN state_name varchar(32) NOT NULL COMMENT '状态机当前状态名';
ALTER TABLE mk_contact_attempt MODIFY COLUMN variables_json text NOT NULL COMMENT '模板变量取值（JSON）';
ALTER TABLE mk_contact_attempt MODIFY COLUMN provider_request_id varchar(256) NULL COMMENT '渠道服务商请求ID（可空）';
ALTER TABLE mk_contact_attempt MODIFY COLUMN provider_code varchar(128) NULL COMMENT '渠道服务商返回码（可空）';
ALTER TABLE mk_contact_attempt MODIFY COLUMN requested_at varchar(40) NOT NULL COMMENT '发起触达时间（ISO-8601 字符串，UTC）';
ALTER TABLE mk_contact_attempt MODIFY COLUMN updated_at varchar(40) NOT NULL COMMENT '最后更新时间（ISO-8601 字符串，UTC）';

-- ---- mk_contact_dlq ----
ALTER TABLE mk_contact_dlq COMMENT = '触达死信表（多次失败进入死信的触达）';
ALTER TABLE mk_contact_dlq MODIFY COLUMN tenant_id varchar(64) NOT NULL COMMENT '租户ID（多租户隔离键，联合主键首段）';
ALTER TABLE mk_contact_dlq MODIFY COLUMN dlq_id varchar(64) NOT NULL COMMENT '死信记录ID';
ALTER TABLE mk_contact_dlq MODIFY COLUMN contact_id varchar(64) NOT NULL COMMENT '关联触达实例ID';
ALTER TABLE mk_contact_dlq MODIFY COLUMN reason_code varchar(128) NOT NULL COMMENT '原因码（枚举）';
ALTER TABLE mk_contact_dlq MODIFY COLUMN state_name varchar(32) NOT NULL COMMENT '状态机当前状态名';
ALTER TABLE mk_contact_dlq MODIFY COLUMN created_at varchar(40) NOT NULL COMMENT '创建时间（ISO-8601 字符串，UTC）';

-- ---- mk_engagement_command ----
ALTER TABLE mk_engagement_command COMMENT = '触达命令表（旅程下发的触达效果命令及执行状态）';
ALTER TABLE mk_engagement_command MODIFY COLUMN tenant_id varchar(64) NOT NULL COMMENT '租户ID（多租户隔离键，联合主键首段）';
ALTER TABLE mk_engagement_command MODIFY COLUMN command_id varchar(64) NOT NULL COMMENT '命令ID（幂等键）';
ALTER TABLE mk_engagement_command MODIFY COLUMN effect_type varchar(32) NOT NULL COMMENT '效果/动作类型（如发放权益、发送触达）';
ALTER TABLE mk_engagement_command MODIFY COLUMN enrollment_id varchar(64) NOT NULL COMMENT '旅程参与实例ID（enrollment）';
ALTER TABLE mk_engagement_command MODIFY COLUMN payload_hash varchar(64) NOT NULL COMMENT '负载哈希（幂等/去重用）';
ALTER TABLE mk_engagement_command MODIFY COLUMN payload_json mediumtext NOT NULL COMMENT '负载内容（JSON）';
ALTER TABLE mk_engagement_command MODIFY COLUMN state_name varchar(32) NOT NULL COMMENT '状态机当前状态名';
ALTER TABLE mk_engagement_command MODIFY COLUMN contact_id varchar(64) NULL COMMENT '生成的触达实例ID（可空）';
ALTER TABLE mk_engagement_command MODIFY COLUMN provider_code varchar(128) NOT NULL DEFAULT '' COMMENT '渠道服务商返回码（空串表示无）';
ALTER TABLE mk_engagement_command MODIFY COLUMN last_error varchar(1000) NOT NULL DEFAULT '' COMMENT '最近一次失败错误信息（空串表示无）';
ALTER TABLE mk_engagement_command MODIFY COLUMN created_at varchar(40) NOT NULL COMMENT '创建时间（ISO-8601 字符串，UTC）';
ALTER TABLE mk_engagement_command MODIFY COLUMN updated_at varchar(40) NOT NULL COMMENT '最后更新时间（ISO-8601 字符串，UTC）';

-- ---- mk_engagement_outbox ----
ALTER TABLE mk_engagement_outbox COMMENT = '触达服务发件箱（触达事件的可靠外发，含重试死信）';
ALTER TABLE mk_engagement_outbox MODIFY COLUMN tenant_id varchar(64) NOT NULL COMMENT '租户ID（多租户隔离键，联合主键首段）';
ALTER TABLE mk_engagement_outbox MODIFY COLUMN event_id varchar(64) NOT NULL COMMENT '发件箱记录ID（本条待发事件ID）';
ALTER TABLE mk_engagement_outbox MODIFY COLUMN contact_id varchar(64) NOT NULL COMMENT '关联触达实例ID';
ALTER TABLE mk_engagement_outbox MODIFY COLUMN event_type varchar(128) NOT NULL COMMENT '事件类型';
ALTER TABLE mk_engagement_outbox MODIFY COLUMN destination_topic varchar(249) NOT NULL COMMENT '目标消息主题（Kafka topic）';
ALTER TABLE mk_engagement_outbox MODIFY COLUMN partition_key varchar(256) NOT NULL COMMENT '分区键（保证同键消息顺序）';
ALTER TABLE mk_engagement_outbox MODIFY COLUMN stream_sequence bigint NOT NULL COMMENT '流内序号（单调递增，保证顺序与去重）';
ALTER TABLE mk_engagement_outbox MODIFY COLUMN payload_json mediumtext NOT NULL COMMENT '负载内容（JSON）';
ALTER TABLE mk_engagement_outbox MODIFY COLUMN publish_attempts int NOT NULL DEFAULT 0 COMMENT '发布尝试次数';
ALTER TABLE mk_engagement_outbox MODIFY COLUMN next_attempt_at varchar(40) NOT NULL COMMENT '下次重试时间（ISO-8601 字符串，UTC）';
ALTER TABLE mk_engagement_outbox MODIFY COLUMN last_error varchar(1000) NOT NULL DEFAULT '' COMMENT '最近一次失败错误信息（空串表示无）';
ALTER TABLE mk_engagement_outbox MODIFY COLUMN created_at varchar(40) NOT NULL COMMENT '创建时间（ISO-8601 字符串，UTC）';
ALTER TABLE mk_engagement_outbox MODIFY COLUMN published_at varchar(40) NULL COMMENT '发布完成时间（NULL 表示尚未发布）';
ALTER TABLE mk_engagement_outbox MODIFY COLUMN dead_lettered_at varchar(40) NULL COMMENT '进入死信时间（NULL 表示未死信）';

-- ---- mk_engagement_outbox_position ----
ALTER TABLE mk_engagement_outbox_position COMMENT = '触达发件箱派发位点表（各触达实例已派发的最新序号）';
ALTER TABLE mk_engagement_outbox_position MODIFY COLUMN tenant_id varchar(64) NOT NULL COMMENT '租户ID（多租户隔离键，联合主键首段）';
ALTER TABLE mk_engagement_outbox_position MODIFY COLUMN contact_id varchar(64) NOT NULL COMMENT '触达实例ID';
ALTER TABLE mk_engagement_outbox_position MODIFY COLUMN last_sequence bigint NOT NULL COMMENT '已处理的最新序号（消费/派发位点）';
ALTER TABLE mk_engagement_outbox_position MODIFY COLUMN updated_at varchar(40) NOT NULL COMMENT '最后更新时间（ISO-8601 字符串，UTC）';

-- ---- mk_frequency_bucket ----
ALTER TABLE mk_frequency_bucket COMMENT = '频次计数桶表（活动+渠道+用户+时间窗的触达计数）';
ALTER TABLE mk_frequency_bucket MODIFY COLUMN tenant_id varchar(64) NOT NULL COMMENT '租户ID（多租户隔离键，联合主键首段）';
ALTER TABLE mk_frequency_bucket MODIFY COLUMN campaign_id varchar(128) NOT NULL COMMENT '营销活动ID';
ALTER TABLE mk_frequency_bucket MODIFY COLUMN channel_name varchar(32) NOT NULL COMMENT '触达渠道名（如 sms/email/push）';
ALTER TABLE mk_frequency_bucket MODIFY COLUMN subject_token varchar(256) NOT NULL COMMENT '受众主体令牌（脱敏后的用户标识）';
ALTER TABLE mk_frequency_bucket MODIFY COLUMN window_bucket bigint NOT NULL COMMENT '时间窗桶编号';
ALTER TABLE mk_frequency_bucket MODIFY COLUMN contact_count int NOT NULL COMMENT '该窗口内已触达次数';
ALTER TABLE mk_frequency_bucket MODIFY COLUMN updated_at varchar(40) NOT NULL COMMENT '最后更新时间（ISO-8601 字符串，UTC）';

-- ---- mk_frequency_policy ----
ALTER TABLE mk_frequency_policy COMMENT = '频次策略表（活动+渠道的触达频控与免打扰时段）';
ALTER TABLE mk_frequency_policy MODIFY COLUMN tenant_id varchar(64) NOT NULL COMMENT '租户ID（多租户隔离键，联合主键首段）';
ALTER TABLE mk_frequency_policy MODIFY COLUMN campaign_id varchar(128) NOT NULL COMMENT '营销活动ID';
ALTER TABLE mk_frequency_policy MODIFY COLUMN channel_name varchar(32) NOT NULL COMMENT '触达渠道名（如 sms/email/push）';
ALTER TABLE mk_frequency_policy MODIFY COLUMN window_seconds bigint NOT NULL COMMENT '频控时间窗（秒）';
ALTER TABLE mk_frequency_policy MODIFY COLUMN max_contacts int NOT NULL COMMENT '窗口内最大触达次数';
ALTER TABLE mk_frequency_policy MODIFY COLUMN quiet_start varchar(16) NOT NULL COMMENT '免打扰开始时间（HH:mm）';
ALTER TABLE mk_frequency_policy MODIFY COLUMN quiet_end varchar(16) NOT NULL COMMENT '免打扰结束时间（HH:mm）';
ALTER TABLE mk_frequency_policy MODIFY COLUMN updated_at varchar(40) NOT NULL COMMENT '最后更新时间（ISO-8601 字符串，UTC）';

-- ---- mk_provider_receipt ----
ALTER TABLE mk_provider_receipt COMMENT = '服务商回执表（渠道服务商的送达/状态回调）';
ALTER TABLE mk_provider_receipt MODIFY COLUMN tenant_id varchar(64) NOT NULL COMMENT '租户ID（多租户隔离键，联合主键首段）';
ALTER TABLE mk_provider_receipt MODIFY COLUMN provider_event_id varchar(256) NOT NULL COMMENT '服务商事件ID（去重用）';
ALTER TABLE mk_provider_receipt MODIFY COLUMN provider_request_id varchar(256) NOT NULL COMMENT '服务商请求ID';
ALTER TABLE mk_provider_receipt MODIFY COLUMN status_name varchar(32) NOT NULL COMMENT '状态名';
ALTER TABLE mk_provider_receipt MODIFY COLUMN occurred_at varchar(40) NOT NULL COMMENT '业务发生时间（ISO-8601 字符串，UTC）';
ALTER TABLE mk_provider_receipt MODIFY COLUMN attributes_json text NOT NULL COMMENT '回执附加属性（JSON）';

-- ---- mk_suppression ----
ALTER TABLE mk_suppression COMMENT = '抑制名单表（各渠道对用户的触达抑制/黑名单）';
ALTER TABLE mk_suppression MODIFY COLUMN tenant_id varchar(64) NOT NULL COMMENT '租户ID（多租户隔离键，联合主键首段）';
ALTER TABLE mk_suppression MODIFY COLUMN subject_token varchar(256) NOT NULL COMMENT '受众主体令牌（脱敏后的用户标识）';
ALTER TABLE mk_suppression MODIFY COLUMN channel_name varchar(32) NOT NULL COMMENT '触达渠道名（如 sms/email/push）';
ALTER TABLE mk_suppression MODIFY COLUMN reason_text varchar(1000) NOT NULL COMMENT '原因说明';
ALTER TABLE mk_suppression MODIFY COLUMN expires_at varchar(40) NULL COMMENT '抑制到期时间（NULL 表示永久）';
ALTER TABLE mk_suppression MODIFY COLUMN updated_at varchar(40) NOT NULL COMMENT '最后更新时间（ISO-8601 字符串，UTC）';

-- ---- mk_template_version ----
ALTER TABLE mk_template_version COMMENT = '消息模板版本表（触达文案模板的每个版本）';
ALTER TABLE mk_template_version MODIFY COLUMN tenant_id varchar(64) NOT NULL COMMENT '租户ID（多租户隔离键，联合主键首段）';
ALTER TABLE mk_template_version MODIFY COLUMN template_id varchar(128) NOT NULL COMMENT '模板ID';
ALTER TABLE mk_template_version MODIFY COLUMN version_no bigint NOT NULL COMMENT '版本号（单调递增）';
ALTER TABLE mk_template_version MODIFY COLUMN channel_name varchar(32) NOT NULL COMMENT '触达渠道名（如 sms/email/push）';
ALTER TABLE mk_template_version MODIFY COLUMN content_text text NOT NULL COMMENT '模板正文内容';
ALTER TABLE mk_template_version MODIFY COLUMN required_variables varchar(2000) NOT NULL COMMENT '模板必填变量列表';
ALTER TABLE mk_template_version MODIFY COLUMN state_name varchar(32) NOT NULL COMMENT '状态机当前状态名';
ALTER TABLE mk_template_version MODIFY COLUMN created_by varchar(128) NOT NULL COMMENT '创建人（操作者标识）';
ALTER TABLE mk_template_version MODIFY COLUMN created_at varchar(40) NOT NULL COMMENT '创建时间（ISO-8601 字符串，UTC）';
