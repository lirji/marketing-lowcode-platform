-- marketing_benefit：为已存在的表与字段补充中文注释（Flyway 前向迁移，幂等 ALTER）
-- 说明：本文件仅添加 COMMENT，不改变任何列的类型/可空/默认值。

-- ---- mk_benefit_definition ----
ALTER TABLE mk_benefit_definition COMMENT = '权益定义表（权益目录的每个版本及发放策略）';
ALTER TABLE mk_benefit_definition MODIFY COLUMN tenant_id varchar(64) NOT NULL COMMENT '租户ID（多租户隔离键，联合主键首段）';
ALTER TABLE mk_benefit_definition MODIFY COLUMN benefit_id varchar(128) NOT NULL COMMENT '权益ID';
ALTER TABLE mk_benefit_definition MODIFY COLUMN version_no bigint NOT NULL COMMENT '版本号（单调递增）';
ALTER TABLE mk_benefit_definition MODIFY COLUMN name_text varchar(256) NOT NULL COMMENT '权益名称';
ALTER TABLE mk_benefit_definition MODIFY COLUMN status_name varchar(32) NOT NULL COMMENT '状态名';
ALTER TABLE mk_benefit_definition MODIFY COLUMN resource_key varchar(256) NOT NULL COMMENT '资源账户键（预算/权益资源标识）';
ALTER TABLE mk_benefit_definition MODIFY COLUMN policy_json mediumtext NOT NULL COMMENT '权益发放策略（JSON）';
ALTER TABLE mk_benefit_definition MODIFY COLUMN created_by varchar(128) NOT NULL COMMENT '创建人（操作者标识）';
ALTER TABLE mk_benefit_definition MODIFY COLUMN created_at varchar(40) NOT NULL COMMENT '创建时间（ISO-8601 字符串，UTC）';

-- ---- mk_benefit_definition_head ----
ALTER TABLE mk_benefit_definition_head COMMENT = '权益定义头指针表（每个权益的最新版本号）';
ALTER TABLE mk_benefit_definition_head MODIFY COLUMN tenant_id varchar(64) NOT NULL COMMENT '租户ID（多租户隔离键，联合主键首段）';
ALTER TABLE mk_benefit_definition_head MODIFY COLUMN benefit_id varchar(128) NOT NULL COMMENT '权益ID';
ALTER TABLE mk_benefit_definition_head MODIFY COLUMN latest_version bigint NOT NULL COMMENT '最新版本号';

-- ---- mk_benefit_outbox ----
ALTER TABLE mk_benefit_outbox COMMENT = '权益服务事务发件箱（可靠事件投递，含重试与死信）';
ALTER TABLE mk_benefit_outbox MODIFY COLUMN tenant_id varchar(64) NOT NULL COMMENT '租户ID（多租户隔离键，联合主键首段）';
ALTER TABLE mk_benefit_outbox MODIFY COLUMN event_id varchar(64) NOT NULL COMMENT '发件箱记录ID（本条待发事件ID）';
ALTER TABLE mk_benefit_outbox MODIFY COLUMN aggregate_id varchar(64) NOT NULL COMMENT '聚合根ID';
ALTER TABLE mk_benefit_outbox MODIFY COLUMN event_type varchar(128) NOT NULL COMMENT '事件类型';
ALTER TABLE mk_benefit_outbox MODIFY COLUMN destination_topic varchar(249) NOT NULL COMMENT '目标消息主题（Kafka topic）';
ALTER TABLE mk_benefit_outbox MODIFY COLUMN partition_key varchar(256) NOT NULL COMMENT '分区键（保证同键消息顺序）';
ALTER TABLE mk_benefit_outbox MODIFY COLUMN stream_sequence bigint NOT NULL COMMENT '流内序号（单调递增，保证顺序与去重）';
ALTER TABLE mk_benefit_outbox MODIFY COLUMN payload_json mediumtext NOT NULL COMMENT '负载内容（JSON）';
ALTER TABLE mk_benefit_outbox MODIFY COLUMN publish_attempts int NOT NULL DEFAULT 0 COMMENT '发布尝试次数';
ALTER TABLE mk_benefit_outbox MODIFY COLUMN next_attempt_at varchar(40) NOT NULL COMMENT '下次重试时间（ISO-8601 字符串，UTC）';
ALTER TABLE mk_benefit_outbox MODIFY COLUMN last_error varchar(1000) NOT NULL DEFAULT '' COMMENT '最近一次失败错误信息（空串表示无）';
ALTER TABLE mk_benefit_outbox MODIFY COLUMN created_at varchar(40) NOT NULL COMMENT '创建时间（ISO-8601 字符串，UTC）';
ALTER TABLE mk_benefit_outbox MODIFY COLUMN published_at varchar(40) NULL COMMENT '发布完成时间（NULL 表示尚未发布）';
ALTER TABLE mk_benefit_outbox MODIFY COLUMN dead_lettered_at varchar(40) NULL COMMENT '进入死信时间（NULL 表示未死信）';

-- ---- mk_benefit_outbox_position ----
ALTER TABLE mk_benefit_outbox_position COMMENT = '权益发件箱派发位点表（各聚合已派发的最新序号）';
ALTER TABLE mk_benefit_outbox_position MODIFY COLUMN tenant_id varchar(64) NOT NULL COMMENT '租户ID（多租户隔离键，联合主键首段）';
ALTER TABLE mk_benefit_outbox_position MODIFY COLUMN aggregate_id varchar(64) NOT NULL COMMENT '聚合根ID';
ALTER TABLE mk_benefit_outbox_position MODIFY COLUMN last_sequence bigint NOT NULL COMMENT '已处理的最新序号（消费/派发位点）';
ALTER TABLE mk_benefit_outbox_position MODIFY COLUMN updated_at varchar(40) NOT NULL COMMENT '最后更新时间（ISO-8601 字符串，UTC）';

-- ---- mk_command_dedup ----
ALTER TABLE mk_command_dedup COMMENT = '权益命令幂等表（按命令ID去重与结果重放）';
ALTER TABLE mk_command_dedup MODIFY COLUMN tenant_id varchar(64) NOT NULL COMMENT '租户ID（多租户隔离键，联合主键首段）';
ALTER TABLE mk_command_dedup MODIFY COLUMN command_id varchar(128) NOT NULL COMMENT '命令ID（幂等键）';
ALTER TABLE mk_command_dedup MODIFY COLUMN payload_hash varchar(64) NOT NULL COMMENT '负载哈希（幂等/去重用）';
ALTER TABLE mk_command_dedup MODIFY COLUMN state_name varchar(32) NOT NULL COMMENT '状态机当前状态名';
ALTER TABLE mk_command_dedup MODIFY COLUMN response_json mediumtext NULL COMMENT '命令处理响应结果（JSON，用于幂等重放返回）';
ALTER TABLE mk_command_dedup MODIFY COLUMN created_at varchar(40) NOT NULL COMMENT '创建时间（ISO-8601 字符串，UTC）';
ALTER TABLE mk_command_dedup MODIFY COLUMN expires_at varchar(40) NOT NULL COMMENT '过期时间（ISO-8601 字符串，UTC）';

-- ---- mk_funding_ledger ----
ALTER TABLE mk_funding_ledger COMMENT = '资金流水台账（资源账户每笔借贷记账，不可变）';
ALTER TABLE mk_funding_ledger MODIFY COLUMN tenant_id varchar(64) NOT NULL COMMENT '租户ID（多租户隔离键，联合主键首段）';
ALTER TABLE mk_funding_ledger MODIFY COLUMN ledger_id varchar(64) NOT NULL COMMENT '台账流水ID';
ALTER TABLE mk_funding_ledger MODIFY COLUMN application_id varchar(64) NOT NULL COMMENT '关联优惠申请ID';
ALTER TABLE mk_funding_ledger MODIFY COLUMN order_id varchar(128) NOT NULL COMMENT '订单ID';
ALTER TABLE mk_funding_ledger MODIFY COLUMN resource_key varchar(256) NOT NULL COMMENT '资源账户键（预算/权益资源标识）';
ALTER TABLE mk_funding_ledger MODIFY COLUMN operation_name varchar(32) NOT NULL COMMENT '操作名';
ALTER TABLE mk_funding_ledger MODIFY COLUMN debit_bucket varchar(32) NOT NULL COMMENT '借方账户桶';
ALTER TABLE mk_funding_ledger MODIFY COLUMN credit_bucket varchar(32) NOT NULL COMMENT '贷方账户桶';
ALTER TABLE mk_funding_ledger MODIFY COLUMN amount_value bigint NOT NULL COMMENT '记账金额（正数）';
ALTER TABLE mk_funding_ledger MODIFY COLUMN account_version bigint NOT NULL COMMENT '资源账户版本号（记账时账户版本）';
ALTER TABLE mk_funding_ledger MODIFY COLUMN correction_of varchar(64) NULL COMMENT '冲正来源流水ID（可空，非冲正为空）';
ALTER TABLE mk_funding_ledger MODIFY COLUMN occurred_at varchar(40) NOT NULL COMMENT '业务发生时间（ISO-8601 字符串，UTC）';

-- ---- mk_journey_benefit_grant ----
ALTER TABLE mk_journey_benefit_grant COMMENT = '旅程权益发放表（旅程节点触发的权益发放命令幂等记录）';
ALTER TABLE mk_journey_benefit_grant MODIFY COLUMN tenant_id varchar(64) NOT NULL COMMENT '租户ID（多租户隔离键，联合主键首段）';
ALTER TABLE mk_journey_benefit_grant MODIFY COLUMN command_id varchar(128) NOT NULL COMMENT '命令ID（幂等键）';
ALTER TABLE mk_journey_benefit_grant MODIFY COLUMN enrollment_id varchar(64) NOT NULL COMMENT '旅程参与实例ID（enrollment）';
ALTER TABLE mk_journey_benefit_grant MODIFY COLUMN subject_token varchar(256) NOT NULL COMMENT '受众主体令牌（脱敏后的用户标识）';
ALTER TABLE mk_journey_benefit_grant MODIFY COLUMN journey_id varchar(128) NOT NULL COMMENT '来源旅程ID';
ALTER TABLE mk_journey_benefit_grant MODIFY COLUMN journey_version bigint NOT NULL COMMENT '来源旅程版本号';
ALTER TABLE mk_journey_benefit_grant MODIFY COLUMN resource_key varchar(256) NOT NULL COMMENT '资源账户键（预算/权益资源标识）';
ALTER TABLE mk_journey_benefit_grant MODIFY COLUMN benefit_id varchar(256) NOT NULL COMMENT '权益ID';
ALTER TABLE mk_journey_benefit_grant MODIFY COLUMN quantity_value bigint NOT NULL COMMENT '发放数量（正数）';
ALTER TABLE mk_journey_benefit_grant MODIFY COLUMN fencing_epoch bigint NOT NULL COMMENT 'fencing 纪元（防脑裂/乱序，单调递增）';
ALTER TABLE mk_journey_benefit_grant MODIFY COLUMN state_name varchar(32) NOT NULL COMMENT '状态机当前状态名';
ALTER TABLE mk_journey_benefit_grant MODIFY COLUMN created_at varchar(40) NOT NULL COMMENT '创建时间（ISO-8601 字符串，UTC）';

-- ---- mk_promotion_application ----
ALTER TABLE mk_promotion_application COMMENT = '优惠申请表（一次下单的优惠应用与生命周期）';
ALTER TABLE mk_promotion_application MODIFY COLUMN tenant_id varchar(64) NOT NULL COMMENT '租户ID（多租户隔离键，联合主键首段）';
ALTER TABLE mk_promotion_application MODIFY COLUMN application_id varchar(64) NOT NULL COMMENT '优惠申请ID';
ALTER TABLE mk_promotion_application MODIFY COLUMN quote_id varchar(128) NOT NULL COMMENT '报价ID（幂等）';
ALTER TABLE mk_promotion_application MODIFY COLUMN decision_request_id varchar(128) NOT NULL COMMENT '关联决策请求ID';
ALTER TABLE mk_promotion_application MODIFY COLUMN order_id varchar(128) NOT NULL COMMENT '订单ID';
ALTER TABLE mk_promotion_application MODIFY COLUMN organization_id varchar(128) NOT NULL COMMENT '组织ID';
ALTER TABLE mk_promotion_application MODIFY COLUMN shop_ids_json text NOT NULL COMMENT '涉及店铺ID列表（JSON）';
ALTER TABLE mk_promotion_application MODIFY COLUMN cart_digest varchar(80) NOT NULL COMMENT '购物车摘要（内容指纹）';
ALTER TABLE mk_promotion_application MODIFY COLUMN token_digest varchar(64) NOT NULL COMMENT '主体令牌摘要（脱敏）';
ALTER TABLE mk_promotion_application MODIFY COLUMN generation_no bigint NOT NULL COMMENT '代次号（每次发布递增）';
ALTER TABLE mk_promotion_application MODIFY COLUMN state_name varchar(32) NOT NULL COMMENT '状态机当前状态名';
ALTER TABLE mk_promotion_application MODIFY COLUMN total_discount bigint NOT NULL COMMENT '优惠总金额（最小币种单位）';
ALTER TABLE mk_promotion_application MODIFY COLUMN currency_code varchar(8) NOT NULL COMMENT '币种代码（ISO 4217）';
ALTER TABLE mk_promotion_application MODIFY COLUMN expires_at varchar(40) NOT NULL COMMENT '过期时间（ISO-8601 字符串，UTC）';
ALTER TABLE mk_promotion_application MODIFY COLUMN created_at varchar(40) NOT NULL COMMENT '创建时间（ISO-8601 字符串，UTC）';
ALTER TABLE mk_promotion_application MODIFY COLUMN updated_at varchar(40) NOT NULL COMMENT '最后更新时间（ISO-8601 字符串，UTC）';
ALTER TABLE mk_promotion_application MODIFY COLUMN expiry_attempts int NOT NULL DEFAULT 0 COMMENT '过期处理尝试次数';
ALTER TABLE mk_promotion_application MODIFY COLUMN expiry_next_attempt_at varchar(40) NOT NULL COMMENT '过期处理下次重试时间（ISO-8601，UTC）';
ALTER TABLE mk_promotion_application MODIFY COLUMN expiry_last_error varchar(1000) NOT NULL DEFAULT '' COMMENT '过期处理最近错误信息（空串表示无）';

-- ---- mk_reservation_item ----
ALTER TABLE mk_reservation_item COMMENT = '预留明细表（一次优惠申请对各资源账户的预留-核销台账）';
ALTER TABLE mk_reservation_item MODIFY COLUMN tenant_id varchar(64) NOT NULL COMMENT '租户ID（多租户隔离键，联合主键首段）';
ALTER TABLE mk_reservation_item MODIFY COLUMN application_id varchar(64) NOT NULL COMMENT '所属优惠申请ID';
ALTER TABLE mk_reservation_item MODIFY COLUMN resource_key varchar(256) NOT NULL COMMENT '资源账户键（预算/权益资源标识）';
ALTER TABLE mk_reservation_item MODIFY COLUMN resource_type varchar(32) NOT NULL COMMENT '资源类型';
ALTER TABLE mk_reservation_item MODIFY COLUMN currency_code varchar(8) NOT NULL COMMENT '币种代码（ISO 4217）';
ALTER TABLE mk_reservation_item MODIFY COLUMN original_amount bigint NOT NULL COMMENT '初始预留金额';
ALTER TABLE mk_reservation_item MODIFY COLUMN reserved_amount bigint NOT NULL COMMENT '当前预留中金额';
ALTER TABLE mk_reservation_item MODIFY COLUMN consumed_amount bigint NOT NULL COMMENT '已核销金额';
ALTER TABLE mk_reservation_item MODIFY COLUMN refunded_amount bigint NOT NULL COMMENT '已退款金额';
ALTER TABLE mk_reservation_item MODIFY COLUMN released_amount bigint NOT NULL COMMENT '已释放金额';
ALTER TABLE mk_reservation_item MODIFY COLUMN reservation_epoch bigint NOT NULL COMMENT '预留纪元（防重复操作）';

-- ---- mk_resource_account ----
ALTER TABLE mk_resource_account COMMENT = '资源账户表（预算/权益额度账户，含预留-核销-归还账务约束）';
ALTER TABLE mk_resource_account MODIFY COLUMN tenant_id varchar(64) NOT NULL COMMENT '租户ID（多租户隔离键，联合主键首段）';
ALTER TABLE mk_resource_account MODIFY COLUMN resource_key varchar(256) NOT NULL COMMENT '资源账户键（预算/权益资源标识）';
ALTER TABLE mk_resource_account MODIFY COLUMN resource_type varchar(32) NOT NULL COMMENT '资源类型';
ALTER TABLE mk_resource_account MODIFY COLUMN currency_code varchar(8) NOT NULL COMMENT '币种代码（ISO 4217）';
ALTER TABLE mk_resource_account MODIFY COLUMN authorized_amount bigint NOT NULL COMMENT '已授权总额度';
ALTER TABLE mk_resource_account MODIFY COLUMN available_amount bigint NOT NULL COMMENT '可用额度';
ALTER TABLE mk_resource_account MODIFY COLUMN reserved_amount bigint NOT NULL COMMENT '已预留额度';
ALTER TABLE mk_resource_account MODIFY COLUMN consumed_amount bigint NOT NULL COMMENT '已核销额度';
ALTER TABLE mk_resource_account MODIFY COLUMN returned_amount bigint NOT NULL COMMENT '已归还额度';
ALTER TABLE mk_resource_account MODIFY COLUMN fencing_epoch bigint NOT NULL COMMENT 'fencing 纪元（防脑裂/乱序，单调递增）';
ALTER TABLE mk_resource_account MODIFY COLUMN version_no bigint NOT NULL COMMENT '版本号（单调递增）';
ALTER TABLE mk_resource_account MODIFY COLUMN state_name varchar(32) NOT NULL COMMENT '状态机当前状态名';
ALTER TABLE mk_resource_account MODIFY COLUMN updated_at varchar(40) NOT NULL COMMENT '最后更新时间（ISO-8601 字符串，UTC）';
