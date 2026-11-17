-- 组合管理以完整slot为边界，防止换清单ID后绕过整体发布。
ALTER TABLE mk_release_slot
    ADD COLUMN bundle_managed BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否已纳入组合管理；只允许由否变为是';

-- 已登记组合也必须受保护；不改变任何已生效代次或激活序号。
UPDATE mk_release_slot slot
JOIN (SELECT DISTINCT tenant_id, environment_name, cell_id, runtime_name, namespace_name
      FROM mk_release_manifest WHERE bundle_id IS NOT NULL) member
  ON slot.tenant_id = member.tenant_id AND slot.environment_name = member.environment_name
 AND slot.cell_id = member.cell_id AND slot.runtime_name = member.runtime_name
 AND slot.namespace_name = member.namespace_name
SET slot.bundle_managed = TRUE;
