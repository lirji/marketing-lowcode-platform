-- 审批页按不可变提交时间与ID排序；状态筛选在索引范围内先于LIMIT执行。
CREATE INDEX ix_approval_tenant_created_case ON mk_approval_case (tenant_id, created_at, case_id)
  COMMENT '审批全状态页的租户提交时间与ID顺序';
CREATE INDEX ix_approval_tenant_status_created_case ON mk_approval_case (tenant_id, status, created_at, case_id)
  COMMENT '审批状态筛选页的租户状态与提交顺序';
