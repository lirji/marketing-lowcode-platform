ALTER TABLE mk_promotion_application
  ADD COLUMN checkout_order_no VARCHAR(25) CHARACTER SET ascii COLLATE ascii_bin NULL
    COMMENT '从可信交易证据取得的展示订单号，资金仍按原生订单标识绑定',
  ADD KEY ix_checkout_order_no (tenant_id, checkout_order_no);
