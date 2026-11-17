-- 组合接收历史升级后同样受保护；此标记不代表任何新配置已生效。
ALTER TABLE mk_journey_runtime_slot
    ADD COLUMN bundle_managed BOOLEAN NOT NULL DEFAULT FALSE COMMENT '该物理slot已由活动组合管理，禁止单清单激活；只增不减';

-- 有旧指针时只置标记；未曾单清单激活时建立不可路由的零代际空slot。
INSERT INTO mk_journey_runtime_slot
    (tenant_id, environment_name, cell_id, namespace_name, desired_generation, activation_sequence,
     directive_signature, directive_json, updated_at, bundle_managed)
SELECT tenant_id, environment_name, cell_id, namespace_name, 0, 0, '', '', MAX(updated_at), TRUE
FROM mk_runtime_bundle_head
WHERE last_sequence IS NOT NULL
GROUP BY tenant_id, environment_name, cell_id, namespace_name
ON DUPLICATE KEY UPDATE bundle_managed = TRUE;
