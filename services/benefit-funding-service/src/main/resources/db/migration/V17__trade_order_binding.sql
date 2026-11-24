CREATE TABLE mk_trade_order_binding (
  tenant_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '营销业务租户标识',
  application_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '营销资金申请标识',
  source_tenant_id bigint NOT NULL COMMENT '获准关联的交易中心原生租户编号',
  source_order_id bigint NOT NULL COMMENT '原始营销签名报价声明的交易中心原生订单编号',
  receipt_event_id char(36) NOT NULL COMMENT '首次绑定采用的交易回执事件标识',
  evidence_version bigint NOT NULL COMMENT '首次绑定采用的原生订单证据版本',
  bound_by varchar(255) NOT NULL COMMENT '通过验证并获准绑定的机器 JWT 主体',
  bound_at varchar(40) NOT NULL COMMENT '首次绑定的 UTC 时间',
  PRIMARY KEY (tenant_id,application_id),
  KEY ix_trade_binding_order (source_tenant_id,source_order_id)
) COMMENT='原生交易订单与营销资金申请的固定关联及首次绑定审计';
