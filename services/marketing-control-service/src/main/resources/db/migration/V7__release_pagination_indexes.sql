-- 先按租户/状态和可见范围筛选，再按不可变创建顺序分页；不改写既有清单与签名。
CREATE INDEX ix_release_tenant_created_manifest ON mk_release_manifest (tenant_id, created_at, manifest_id)
  COMMENT '发布全状态页的租户创建时间与ID顺序';
CREATE INDEX ix_release_tenant_state_created_manifest ON mk_release_manifest (tenant_id, state_name, created_at, manifest_id)
  COMMENT '发布状态筛选页的租户状态与创建顺序';
