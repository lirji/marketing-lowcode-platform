CREATE TABLE mk_award_fulfillment (
  tenant_id varchar(64) NOT NULL COMMENT '业务租户，与原发奖意图一致',
  intent_id varchar(64) NOT NULL COMMENT '原发奖意图标识，每条意图仅保留一个当前观察',
  sync_state varchar(16) NOT NULL DEFAULT 'PENDING' COMMENT '查询任务状态：PENDING、RUNNING、PAUSED，不能作为发放业务状态',
  benefit_order_no varchar(64) NULL COMMENT '经过来源身份核对的权益中心订单号',
  center_status varchar(32) NULL COMMENT '最近成功查询返回的权益中心订单状态，不代表永久终态',
  items_json text NULL COMMENT '经过原始意图校验的项状态，不含收件主体或凭据',
  checked_at varchar(40) NULL COMMENT '最近一次成功查询完成时间，UTC；非权益发生时间',
  failure_count int NOT NULL DEFAULT 0 COMMENT '连续查询失败次数，成功后清零',
  error_code varchar(64) NOT NULL DEFAULT '' COMMENT '最近同步错误的稳定原因码，不含远程响应正文',
  next_attempt_at varchar(40) NOT NULL COMMENT '下一次可查询时间，UTC',
  lease_owner varchar(96) NULL COMMENT '当前查询执行者，租约到期后可恢复',
  lease_version bigint NOT NULL DEFAULT 0 COMMENT '递增查询租约代次，隔离迟到结果',
  lease_until varchar(40) NULL COMMENT '查询租约到期时间，UTC',
  created_at varchar(40) NOT NULL COMMENT '首次创建观察任务的时间，UTC',
  updated_at varchar(40) NOT NULL COMMENT '最近任务或观察更新时间，UTC',
  PRIMARY KEY (tenant_id,intent_id),
  KEY ix_award_fulfillment_due (sync_state,next_attempt_at,tenant_id,intent_id),
  CONSTRAINT fk_award_fulfillment_intent FOREIGN KEY (tenant_id,intent_id)
    REFERENCES mk_award_intent_outbox(tenant_id,intent_id),
  CONSTRAINT ck_award_fulfillment_state CHECK (sync_state IN ('PENDING','RUNNING','PAUSED')),
  CONSTRAINT ck_award_fulfillment_failures CHECK (failure_count >= 0)
) COMMENT='普通营销发奖的持久履约观察与有界查询恢复任务';
