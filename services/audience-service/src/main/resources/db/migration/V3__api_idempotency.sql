CREATE TABLE mk_api_command (
  tenant_id varchar(64) NOT NULL COMMENT '租户ID（多租户隔离键）',
  operation_name varchar(128) NOT NULL COMMENT '稳定API操作名',
  idempotency_key varchar(128) NOT NULL COMMENT '调用方提供的Idempotency-Key',
  payload_hash varchar(64) NOT NULL COMMENT '规范化请求负载SHA-256摘要',
  state_name varchar(16) NOT NULL COMMENT '命令状态：PROCESSING或COMPLETED',
  response_json mediumtext NULL COMMENT '首次成功响应JSON，用于原样语义重放',
  created_at varchar(40) NOT NULL COMMENT '首次请求时间（ISO-8601字符串，UTC）',
  updated_at varchar(40) NOT NULL COMMENT '最后更新时间（ISO-8601字符串，UTC）',
  expires_at varchar(40) NOT NULL COMMENT '幂等记录过期时间（ISO-8601字符串，UTC）',
  PRIMARY KEY (tenant_id, operation_name, idempotency_key),
  CONSTRAINT ck_audience_api_command_state CHECK (state_name IN ('PROCESSING','COMPLETED'))
) COMMENT='Audience写接口统一幂等记录（负载绑定与首次响应重放）';

CREATE INDEX ix_audience_api_command_expiry ON mk_api_command (expires_at);
