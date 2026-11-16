ALTER TABLE mk_decision_command
  ADD COLUMN trace_json mediumtext NULL COMMENT '自动生成的脱敏决策摘要，不含原始主体、购物车或OfferToken；旧记录为空',
  ADD COLUMN trace_request_hash char(64) NULL COMMENT '请求ID的SHA-256查询索引，返回前核对原始请求ID',
  ADD COLUMN trace_order_hash char(64) NULL COMMENT '订单ID的SHA-256查询索引，无订单时为空',
  ADD COLUMN trace_sort varchar(80) NULL COMMENT '决策UTC纳秒时间和quoteId稳定排序键，不以网络回包顺序决定最新';
CREATE INDEX ix_command_trace_request ON mk_decision_command(tenant_id,trace_request_hash,trace_sort);
CREATE INDEX ix_command_trace_order ON mk_decision_command(tenant_id,trace_order_hash,trace_sort);
