CREATE TABLE mk_award_intent_outbox (
  tenant_id varchar(64) NOT NULL COMMENT '租户ID（多租户隔离键）',
  intent_id varchar(64) NOT NULL COMMENT '营销发放意图ID',
  source_system varchar(64) NOT NULL COMMENT '提交权益中台使用的稳定来源系统标识',
  source_request_id varchar(128) NOT NULL COMMENT '跨系统幂等键',
  campaign_id varchar(64) NOT NULL COMMENT '来源营销活动ID',
  definition_version bigint NOT NULL COMMENT '来源营销定义版本号',
  subject_hash varchar(64) NOT NULL COMMENT '租户加盐后的主体SHA-256摘要',
  delivery_mode varchar(16) NOT NULL COMMENT '发放模式：LEGACY、SHADOW或CENTER',
  request_hash varchar(64) NOT NULL COMMENT '触发请求摘要，用于幂等冲突检测',
  payload_hash varchar(64) NOT NULL COMMENT 'AwardIntent规范JSON摘要',
  payload_json mediumtext NOT NULL COMMENT '发往权益中台的AwardIntent规范JSON；LEGACY模式为空对象',
  status_name varchar(16) NOT NULL COMMENT '内部投递状态：PENDING、SENDING、SENT或DEAD',
  delivery_result varchar(32) NOT NULL COMMENT '路由或投递结果：LEGACY_OWNED、SHADOW_RECORDED、CENTER_ENQUEUED或CENTER_ACCEPTED',
  attempt_count integer NOT NULL DEFAULT 0 COMMENT '权益中台投递尝试次数',
  next_attempt_at varchar(40) NOT NULL COMMENT '下次可投递时间（ISO-8601字符串，UTC）',
  lease_owner varchar(128) NULL COMMENT '当前投递租约持有者',
  lease_until varchar(40) NULL COMMENT '当前投递租约截止时间（ISO-8601字符串，UTC）',
  lease_version bigint NOT NULL DEFAULT 0 COMMENT '投递租约世代，防止过期worker回写新租约结果',
  benefit_order_no varchar(64) NULL COMMENT '权益中台受理后返回的订单号',
  last_error varchar(1000) NOT NULL DEFAULT '' COMMENT '最近一次投递失败信息',
  created_at varchar(40) NOT NULL COMMENT '创建时间（ISO-8601字符串，UTC）',
  updated_at varchar(40) NOT NULL COMMENT '最后更新时间（ISO-8601字符串，UTC）',
  sent_at varchar(40) NULL COMMENT '权益中台受理时间（ISO-8601字符串，UTC）',
  PRIMARY KEY (tenant_id, intent_id),
  CONSTRAINT uq_award_intent_source UNIQUE (tenant_id, source_system, source_request_id),
  CONSTRAINT ck_award_intent_definition_version CHECK (definition_version > 0),
  CONSTRAINT ck_award_intent_attempt_count CHECK (attempt_count >= 0),
  CONSTRAINT ck_award_intent_lease_version CHECK (lease_version >= 0),
  CONSTRAINT ck_award_intent_mode CHECK (delivery_mode IN ('LEGACY','SHADOW','CENTER')),
  CONSTRAINT ck_award_intent_status CHECK (status_name IN ('PENDING','SENDING','SENT','DEAD'))
) COMMENT='营销发放意图事务发件箱（租户灰度、幂等投递及只读状态）';

CREATE INDEX ix_award_intent_campaign
    ON mk_award_intent_outbox (tenant_id, campaign_id, created_at, intent_id);

CREATE INDEX ix_award_intent_due
    ON mk_award_intent_outbox (delivery_mode, status_name, next_attempt_at, lease_until, created_at);
