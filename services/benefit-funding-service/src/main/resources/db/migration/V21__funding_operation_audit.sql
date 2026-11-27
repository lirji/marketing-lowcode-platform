CREATE TABLE mk_funding_audit_attempt (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '营销资金来源尝试流水',
  tenant_id VARCHAR(64) NOT NULL COMMENT '可信营销租户',
  request_id VARCHAR(36) NOT NULL COMMENT '本次调用的服务端引用',
  actor_id VARCHAR(255) NOT NULL COMMENT '可信人员或服务身份',
  action_code VARCHAR(64) NOT NULL COMMENT '稳定动作编码',
  trace_id VARCHAR(64) NULL COMMENT '经过格式校验的追踪引用',
  occurred_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '进入业务用例的UTC时间',
  policy_version SMALLINT NOT NULL COMMENT '审计保留策略版本',
  PRIMARY KEY (id),
  UNIQUE KEY uk_funding_audit_request (request_id),
  KEY ix_funding_audit_attempt_scope (tenant_id,id)
) COMMENT='营销资金操作审计尝试';

CREATE TABLE mk_funding_audit_result (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '追加事实流水',
  tenant_id VARCHAR(64) NOT NULL COMMENT '可信营销租户',
  attempt_id BIGINT NOT NULL COMMENT '所属尝试引用',
  application_id VARCHAR(64) NULL COMMENT '判权后确认的资金申请',
  application_no VARCHAR(25) NULL COMMENT '资金申请可读编号',
  order_id VARCHAR(128) NULL COMMENT '资金申请持有的原订单关联',
  order_no VARCHAR(25) NULL COMMENT '从交易证据确认的可读订单号',
  campaign_id VARCHAR(128) NULL COMMENT '原资金活动引用',
  command_hash VARCHAR(64) NULL COMMENT '命令键摘要，用于关联同一实际效果',
  action_code VARCHAR(64) NOT NULL COMMENT '稳定资金动作',
  result_code VARCHAR(24) NOT NULL COMMENT '成功、重试、拒绝或未知结果码',
  error_code VARCHAR(64) NULL COMMENT '原稳定错误码，无原始异常详情',
  original_effect_id BIGINT NULL COMMENT '重复请求关联的原提交',
  before_state VARCHAR(32) NULL COMMENT '本次实际操作前业务状态',
  after_state VARCHAR(32) NULL COMMENT '本次实际操作后业务状态',
  amount_minor BIGINT NULL COMMENT '本次实际变化的预算金额，以最小货币单位保存',
  currency VARCHAR(8) NULL COMMENT '实际预算结算币种',
  recorded_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '已经确认事实的UTC时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_funding_audit_outcome (attempt_id),
  KEY ix_funding_audit_application (tenant_id,application_id,id),
  KEY ix_funding_audit_order (tenant_id,order_id,id),
  KEY ix_funding_audit_command (tenant_id,application_id,action_code,command_hash,id)
) COMMENT='营销资金操作结果与业务提交证据';
