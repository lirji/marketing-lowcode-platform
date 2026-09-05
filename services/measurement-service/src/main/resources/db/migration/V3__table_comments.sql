-- marketing_measurement：为已存在的表与字段补充中文注释（Flyway 前向迁移，幂等 ALTER）
-- 说明：本文件仅添加 COMMENT，不改变任何列的类型/可空/默认值。

-- ---- mk_attribution_credit ----
ALTER TABLE mk_attribution_credit COMMENT = '归因信用表（转化对各触点的归因权重与金额分配）';
ALTER TABLE mk_attribution_credit MODIFY COLUMN tenant_id varchar(64) NOT NULL COMMENT '租户ID（多租户隔离键，联合主键首段）';
ALTER TABLE mk_attribution_credit MODIFY COLUMN conversion_event_id varchar(128) NOT NULL COMMENT '转化事件ID';
ALTER TABLE mk_attribution_credit MODIFY COLUMN policy_name varchar(32) NOT NULL COMMENT '归因策略名';
ALTER TABLE mk_attribution_credit MODIFY COLUMN touch_event_id varchar(128) NOT NULL COMMENT '触点事件ID';
ALTER TABLE mk_attribution_credit MODIFY COLUMN credit_value decimal(20,8) NOT NULL COMMENT '归因权重（0~1，高精度小数）';
ALTER TABLE mk_attribution_credit MODIFY COLUMN revenue_minor bigint NOT NULL COMMENT '收入金额（最小币种单位，如分）';
ALTER TABLE mk_attribution_credit MODIFY COLUMN calculated_at varchar(40) NOT NULL COMMENT '计算时间（ISO-8601 字符串，UTC）';

-- ---- mk_attribution_recompute_lock ----
ALTER TABLE mk_attribution_recompute_lock COMMENT = '归因重算锁表（每租户归因重算的互斥锁）';
ALTER TABLE mk_attribution_recompute_lock MODIFY COLUMN tenant_id varchar(64) NOT NULL COMMENT '租户ID（多租户隔离键，联合主键首段）';
ALTER TABLE mk_attribution_recompute_lock MODIFY COLUMN updated_at varchar(40) NOT NULL COMMENT '最后更新时间（ISO-8601 字符串，UTC）';

-- ---- mk_dashboard_projection_delta ----
ALTER TABLE mk_dashboard_projection_delta COMMENT = '看板投影增量表（面向看板聚合的幂等增量流水）';
ALTER TABLE mk_dashboard_projection_delta MODIFY COLUMN tenant_id varchar(64) NOT NULL COMMENT '租户ID（多租户隔离键，联合主键首段）';
ALTER TABLE mk_dashboard_projection_delta MODIFY COLUMN delta_id varchar(64) NOT NULL COMMENT '增量记录ID';
ALTER TABLE mk_dashboard_projection_delta MODIFY COLUMN root_event_id varchar(128) NOT NULL COMMENT '根事件ID（冲正链根）';
ALTER TABLE mk_dashboard_projection_delta MODIFY COLUMN source_event_id varchar(128) NOT NULL COMMENT '来源事实事件ID';
ALTER TABLE mk_dashboard_projection_delta MODIFY COLUMN revision_no bigint NOT NULL COMMENT '修订号（同一根事件的第几次修订）';
ALTER TABLE mk_dashboard_projection_delta MODIFY COLUMN operation_name varchar(16) NOT NULL COMMENT '操作名';
ALTER TABLE mk_dashboard_projection_delta MODIFY COLUMN fact_type varchar(64) NOT NULL COMMENT '事实类型';
ALTER TABLE mk_dashboard_projection_delta MODIFY COLUMN business_key varchar(256) NOT NULL COMMENT '业务键（聚合内幂等键）';
ALTER TABLE mk_dashboard_projection_delta MODIFY COLUMN campaign_id varchar(128) NOT NULL COMMENT '营销活动ID';
ALTER TABLE mk_dashboard_projection_delta MODIFY COLUMN experiment_id varchar(128) NOT NULL COMMENT '实验ID';
ALTER TABLE mk_dashboard_projection_delta MODIFY COLUMN variant_id varchar(128) NOT NULL COMMENT '实验变体ID';
ALTER TABLE mk_dashboard_projection_delta MODIFY COLUMN subject_hash varchar(64) NOT NULL COMMENT '受众主体哈希（脱敏后的用户标识）';
ALTER TABLE mk_dashboard_projection_delta MODIFY COLUMN count_delta bigint NOT NULL COMMENT '计数增量';
ALTER TABLE mk_dashboard_projection_delta MODIFY COLUMN revenue_delta_minor bigint NOT NULL COMMENT '收入增量（最小币种单位）';
ALTER TABLE mk_dashboard_projection_delta MODIFY COLUMN cost_delta_minor bigint NOT NULL COMMENT '成本增量（最小币种单位）';
ALTER TABLE mk_dashboard_projection_delta MODIFY COLUMN occurred_at varchar(40) NOT NULL COMMENT '业务发生时间（ISO-8601 字符串，UTC）';
ALTER TABLE mk_dashboard_projection_delta MODIFY COLUMN ingested_at varchar(40) NOT NULL COMMENT '写入/摄取时间（ISO-8601 字符串，UTC）';
ALTER TABLE mk_dashboard_projection_delta MODIFY COLUMN payload_hash varchar(64) NOT NULL COMMENT '负载哈希（幂等/去重用）';
ALTER TABLE mk_dashboard_projection_delta MODIFY COLUMN source_topic varchar(249) NOT NULL COMMENT '来源消息主题';
ALTER TABLE mk_dashboard_projection_delta MODIFY COLUMN source_partition int NULL COMMENT '来源消息分区（可空）';
ALTER TABLE mk_dashboard_projection_delta MODIFY COLUMN source_offset bigint NULL COMMENT '来源消息位点（可空）';
ALTER TABLE mk_dashboard_projection_delta MODIFY COLUMN projected_at varchar(40) NOT NULL COMMENT '投影处理时间（ISO-8601 字符串，UTC）';

-- ---- mk_decision_trace ----
ALTER TABLE mk_decision_trace COMMENT = '决策轨迹表（每次发放决策的可追溯明细，支持法务保留）';
ALTER TABLE mk_decision_trace MODIFY COLUMN tenant_id varchar(64) NOT NULL COMMENT '租户ID（多租户隔离键，联合主键首段）';
ALTER TABLE mk_decision_trace MODIFY COLUMN trace_id varchar(128) NOT NULL COMMENT '决策轨迹ID';
ALTER TABLE mk_decision_trace MODIFY COLUMN request_id varchar(128) NOT NULL COMMENT '决策请求ID（去重用）';
ALTER TABLE mk_decision_trace MODIFY COLUMN order_id varchar(128) NOT NULL COMMENT '订单ID';
ALTER TABLE mk_decision_trace MODIFY COLUMN subject_hash varchar(64) NOT NULL COMMENT '受众主体哈希（脱敏后的用户标识）';
ALTER TABLE mk_decision_trace MODIFY COLUMN generation_no bigint NOT NULL COMMENT '代次号（每次发布递增）';
ALTER TABLE mk_decision_trace MODIFY COLUMN duration_micros bigint NOT NULL COMMENT '决策耗时（微秒）';
ALTER TABLE mk_decision_trace MODIFY COLUMN candidates_json text NOT NULL COMMENT '候选方案明细（JSON）';
ALTER TABLE mk_decision_trace MODIFY COLUMN pricing_json text NOT NULL COMMENT '定价/优惠计算明细（JSON）';
ALTER TABLE mk_decision_trace MODIFY COLUMN terms_version varchar(128) NOT NULL COMMENT '命中的条款版本';
ALTER TABLE mk_decision_trace MODIFY COLUMN expires_at varchar(40) NOT NULL COMMENT '过期时间（ISO-8601 字符串，UTC）';
ALTER TABLE mk_decision_trace MODIFY COLUMN legal_hold tinyint(1) NOT NULL COMMENT '是否法务保留（1=是，不受常规过期清理）';
ALTER TABLE mk_decision_trace MODIFY COLUMN created_at varchar(40) NOT NULL COMMENT '创建时间（ISO-8601 字符串，UTC）';

-- ---- mk_experiment ----
ALTER TABLE mk_experiment COMMENT = '实验定义表（A/B 实验的每个版本定义）';
ALTER TABLE mk_experiment MODIFY COLUMN tenant_id varchar(64) NOT NULL COMMENT '租户ID（多租户隔离键，联合主键首段）';
ALTER TABLE mk_experiment MODIFY COLUMN experiment_id varchar(128) NOT NULL COMMENT '实验ID';
ALTER TABLE mk_experiment MODIFY COLUMN version_no varchar(64) NOT NULL COMMENT '实验版本号（字符串）';
ALTER TABLE mk_experiment MODIFY COLUMN layer_name varchar(128) NOT NULL COMMENT '实验层名（同层实验互斥分流）';
ALTER TABLE mk_experiment MODIFY COLUMN definition_json text NOT NULL COMMENT '实验定义内容（JSON）';
ALTER TABLE mk_experiment MODIFY COLUMN state_name varchar(32) NOT NULL COMMENT '状态机当前状态名';
ALTER TABLE mk_experiment MODIFY COLUMN created_at varchar(40) NOT NULL COMMENT '创建时间（ISO-8601 字符串，UTC）';

-- ---- mk_experiment_assignment ----
ALTER TABLE mk_experiment_assignment COMMENT = '实验分组表（随机单元在某实验版本的变体分配）';
ALTER TABLE mk_experiment_assignment MODIFY COLUMN tenant_id varchar(64) NOT NULL COMMENT '租户ID（多租户隔离键，联合主键首段）';
ALTER TABLE mk_experiment_assignment MODIFY COLUMN experiment_id varchar(128) NOT NULL COMMENT '实验ID';
ALTER TABLE mk_experiment_assignment MODIFY COLUMN version_no varchar(64) NOT NULL COMMENT '实验版本号（字符串）';
ALTER TABLE mk_experiment_assignment MODIFY COLUMN layer_name varchar(128) NOT NULL COMMENT '实验层名（同层实验互斥分流）';
ALTER TABLE mk_experiment_assignment MODIFY COLUMN randomization_unit varchar(256) NOT NULL COMMENT '随机化单元（分桶主体标识）';
ALTER TABLE mk_experiment_assignment MODIFY COLUMN variant_id varchar(128) NOT NULL COMMENT '实验变体ID';
ALTER TABLE mk_experiment_assignment MODIFY COLUMN holdout_value tinyint(1) NOT NULL COMMENT '是否对照/留出组（1=是，0=否）';
ALTER TABLE mk_experiment_assignment MODIFY COLUMN bucket_no int NOT NULL COMMENT '分桶编号';
ALTER TABLE mk_experiment_assignment MODIFY COLUMN assigned_at varchar(40) NOT NULL COMMENT '分配时间（ISO-8601 字符串，UTC）';

-- ---- mk_experiment_layer_assignment ----
ALTER TABLE mk_experiment_layer_assignment COMMENT = '实验层分配表（随机单元在某实验层命中的实验）';
ALTER TABLE mk_experiment_layer_assignment MODIFY COLUMN tenant_id varchar(64) NOT NULL COMMENT '租户ID（多租户隔离键，联合主键首段）';
ALTER TABLE mk_experiment_layer_assignment MODIFY COLUMN layer_name varchar(128) NOT NULL COMMENT '实验层名（同层实验互斥分流）';
ALTER TABLE mk_experiment_layer_assignment MODIFY COLUMN randomization_unit varchar(256) NOT NULL COMMENT '随机化单元（分桶主体标识）';
ALTER TABLE mk_experiment_layer_assignment MODIFY COLUMN experiment_id varchar(128) NOT NULL COMMENT '实验ID';
ALTER TABLE mk_experiment_layer_assignment MODIFY COLUMN assigned_at varchar(40) NOT NULL COMMENT '分配时间（ISO-8601 字符串，UTC）';

-- ---- mk_fact ----
ALTER TABLE mk_fact COMMENT = '度量事实表（转化/曝光等原子事实，支持冲正与实验归因）';
ALTER TABLE mk_fact MODIFY COLUMN tenant_id varchar(64) NOT NULL COMMENT '租户ID（多租户隔离键，联合主键首段）';
ALTER TABLE mk_fact MODIFY COLUMN event_id varchar(128) NOT NULL COMMENT '事实事件ID';
ALTER TABLE mk_fact MODIFY COLUMN fact_type varchar(64) NOT NULL COMMENT '事实类型';
ALTER TABLE mk_fact MODIFY COLUMN business_key varchar(256) NOT NULL COMMENT '业务键（聚合内幂等键）';
ALTER TABLE mk_fact MODIFY COLUMN subject_hash varchar(64) NOT NULL COMMENT '受众主体哈希（脱敏后的用户标识）';
ALTER TABLE mk_fact MODIFY COLUMN occurred_at varchar(40) NOT NULL COMMENT '业务发生时间（ISO-8601 字符串，UTC）';
ALTER TABLE mk_fact MODIFY COLUMN ingested_at varchar(40) NOT NULL COMMENT '写入/摄取时间（ISO-8601 字符串，UTC）';
ALTER TABLE mk_fact MODIFY COLUMN schema_version varchar(32) NOT NULL COMMENT '事实 schema 版本';
ALTER TABLE mk_fact MODIFY COLUMN payload_hash varchar(64) NOT NULL COMMENT '负载哈希（幂等/去重用）';
ALTER TABLE mk_fact MODIFY COLUMN attributes_json text NOT NULL COMMENT '事实属性（JSON）';
ALTER TABLE mk_fact MODIFY COLUMN correction_of varchar(128) NOT NULL COMMENT '冲正来源事件ID（非冲正为空串）';
ALTER TABLE mk_fact MODIFY COLUMN correction_root_id varchar(128) NOT NULL COMMENT '冲正链根事件ID';
ALTER TABLE mk_fact MODIFY COLUMN corrected_value tinyint(1) NOT NULL COMMENT '是否已被冲正（1=是，0=否）';
ALTER TABLE mk_fact MODIFY COLUMN revenue_minor bigint NOT NULL COMMENT '收入金额（最小币种单位，如分）';
ALTER TABLE mk_fact MODIFY COLUMN cost_minor bigint NOT NULL COMMENT '成本金额（最小币种单位，如分）';
ALTER TABLE mk_fact MODIFY COLUMN experiment_id varchar(128) NOT NULL COMMENT '实验ID';
ALTER TABLE mk_fact MODIFY COLUMN experiment_version varchar(64) NOT NULL COMMENT '实验版本号';
ALTER TABLE mk_fact MODIFY COLUMN variant_id varchar(128) NOT NULL COMMENT '实验变体ID';

-- ---- mk_measurement_command ----
ALTER TABLE mk_measurement_command COMMENT = '度量命令幂等表（按命令ID去重与结果重放）';
ALTER TABLE mk_measurement_command MODIFY COLUMN tenant_id varchar(64) NOT NULL COMMENT '租户ID（多租户隔离键，联合主键首段）';
ALTER TABLE mk_measurement_command MODIFY COLUMN command_id varchar(128) NOT NULL COMMENT '命令ID（幂等键）';
ALTER TABLE mk_measurement_command MODIFY COLUMN payload_hash varchar(64) NOT NULL COMMENT '负载哈希（幂等/去重用）';
ALTER TABLE mk_measurement_command MODIFY COLUMN state_name varchar(32) NOT NULL COMMENT '状态机当前状态名';
ALTER TABLE mk_measurement_command MODIFY COLUMN response_json mediumtext NULL COMMENT '命令处理响应结果（JSON，用于幂等重放返回）';
ALTER TABLE mk_measurement_command MODIFY COLUMN created_at varchar(40) NOT NULL COMMENT '创建时间（ISO-8601 字符串，UTC）';
ALTER TABLE mk_measurement_command MODIFY COLUMN expires_at varchar(40) NOT NULL COMMENT '过期时间（ISO-8601 字符串，UTC）';

-- ---- mk_projection_partition_watermark ----
ALTER TABLE mk_projection_partition_watermark COMMENT = '投影分区水位表（每个投影分区的处理进度水位）';
ALTER TABLE mk_projection_partition_watermark MODIFY COLUMN tenant_id varchar(64) NOT NULL COMMENT '租户ID（多租户隔离键，联合主键首段）';
ALTER TABLE mk_projection_partition_watermark MODIFY COLUMN projection_name varchar(128) NOT NULL COMMENT '投影名';
ALTER TABLE mk_projection_partition_watermark MODIFY COLUMN partition_id int NOT NULL COMMENT '分区ID';
ALTER TABLE mk_projection_partition_watermark MODIFY COLUMN source_offset bigint NOT NULL COMMENT '来源消息位点 offset';
ALTER TABLE mk_projection_partition_watermark MODIFY COLUMN complete_through_epoch_ms bigint NOT NULL COMMENT '已完整处理到的时间水位（epoch 毫秒）';
ALTER TABLE mk_projection_partition_watermark MODIFY COLUMN updated_at varchar(40) NOT NULL COMMENT '最后更新时间（ISO-8601 字符串，UTC）';

-- ---- mk_projection_watermark_config ----
ALTER TABLE mk_projection_watermark_config COMMENT = '投影水位配置表（每个投影的分区数配置）';
ALTER TABLE mk_projection_watermark_config MODIFY COLUMN tenant_id varchar(64) NOT NULL COMMENT '租户ID（多租户隔离键，联合主键首段）';
ALTER TABLE mk_projection_watermark_config MODIFY COLUMN projection_name varchar(128) NOT NULL COMMENT '投影名';
ALTER TABLE mk_projection_watermark_config MODIFY COLUMN partition_count int NOT NULL COMMENT '投影分区数';
ALTER TABLE mk_projection_watermark_config MODIFY COLUMN updated_at varchar(40) NOT NULL COMMENT '最后更新时间（ISO-8601 字符串，UTC）';
