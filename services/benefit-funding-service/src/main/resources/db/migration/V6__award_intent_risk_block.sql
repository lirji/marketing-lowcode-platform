CREATE TABLE mk_award_intent_dedupe (
  tenant_id varchar(64) NOT NULL COMMENT '租户ID（多租户隔离键）',
  source_system varchar(64) NOT NULL COMMENT '提交权益中台使用的稳定来源系统标识',
  source_request_id varchar(128) NOT NULL COMMENT '跨系统幂等键',
  request_hash varchar(64) NOT NULL COMMENT '触发请求摘要，用于跨发件箱和拦截表的幂等冲突检测',
  result_type varchar(16) NOT NULL COMMENT '首次持久化结果类型：OUTBOX或BLOCK',
  created_at varchar(40) NOT NULL COMMENT '首次结果创建时间（ISO-8601字符串，UTC）',
  updated_at varchar(40) NOT NULL COMMENT '最后更新时间（ISO-8601字符串，UTC）',
  PRIMARY KEY (tenant_id, source_system, source_request_id),
  CONSTRAINT ck_award_intent_dedupe_result CHECK (result_type IN ('OUTBOX','BLOCK'))
) COMMENT='营销发放意图跨发件箱与风控拦截表的并发幂等仲裁键';

CREATE TABLE mk_award_intent_block (
  tenant_id varchar(64) NOT NULL COMMENT '租户ID（多租户隔离键）',
  intent_id varchar(64) NOT NULL COMMENT '被风控拦截的营销发放意图ID，也是列表seek游标',
  source_system varchar(64) NOT NULL COMMENT '提交权益中台使用的稳定来源系统标识',
  source_request_id varchar(128) NOT NULL COMMENT '跨系统幂等键',
  campaign_id varchar(64) NOT NULL COMMENT '来源营销活动ID',
  definition_version bigint NOT NULL COMMENT '来源营销定义版本号',
  subject_hash varchar(64) NOT NULL COMMENT '租户加盐后的主体SHA-256摘要',
  delivery_mode varchar(16) NOT NULL COMMENT '首次决策时固化的发放模式：LEGACY、SHADOW或CENTER',
  request_hash varchar(64) NOT NULL COMMENT '触发请求摘要，用于幂等冲突检测',
  risk_action varchar(16) NOT NULL COMMENT '风控结果：CHALLENGE、REVIEW、REJECT或UNAVAILABLE',
  risk_reason varchar(1000) NOT NULL DEFAULT '' COMMENT '风控命中规则或不可用原因，不与投递失败原因混用',
  risk_decision_id varchar(128) NULL COMMENT 'risk-platform返回的决策ID；调用不可用时为空',
  created_at varchar(40) NOT NULL COMMENT '创建时间（ISO-8601字符串，UTC）',
  updated_at varchar(40) NOT NULL COMMENT '最后更新时间（ISO-8601字符串，UTC）',
  PRIMARY KEY (tenant_id, intent_id),
  CONSTRAINT uq_award_intent_block_source UNIQUE (tenant_id, source_system, source_request_id),
  CONSTRAINT ck_award_intent_block_definition_version CHECK (definition_version > 0),
  CONSTRAINT ck_award_intent_block_mode CHECK (delivery_mode IN ('LEGACY','SHADOW','CENTER')),
  CONSTRAINT ck_award_intent_block_action CHECK (risk_action IN ('CHALLENGE','REVIEW','REJECT','UNAVAILABLE'))
) COMMENT='营销发放前风控拦截读模型；拦截行永不进入发件箱';

CREATE INDEX ix_award_intent_block_campaign
    ON mk_award_intent_block (tenant_id, campaign_id, created_at, intent_id);
