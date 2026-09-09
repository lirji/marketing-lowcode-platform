-- 只保存 VERIFIED 制品；不添加 READY、激活或许可状态，避免迁移被误认为开放发布。
CREATE TABLE mk_referral_verified_release (
    tenant_id VARCHAR(64) NOT NULL COMMENT '已认证服务上下文租户',
    environment VARCHAR(64) NOT NULL COMMENT '签名清单部署环境',
    cell VARCHAR(64) NOT NULL COMMENT '签名清单部署单元',
    namespace VARCHAR(128) NOT NULL COMMENT '签名清单发布命名空间',
    generation BIGINT NOT NULL COMMENT '槽位内不可变发布代次',
    manifest_id VARCHAR(160) NOT NULL COMMENT '签名清单标识',
    release_key_id VARCHAR(128) NOT NULL COMMENT '验签使用的受控发布公钥标识',
    manifest_json MEDIUMTEXT NOT NULL COMMENT '完整签名清单用于恢复后重新校验',
    artifact_payload MEDIUMBLOB NOT NULL COMMENT '已通过签名摘要校验的原始制品字节',
    verified_at DATETIME(6) NOT NULL COMMENT '首次持久验证时间UTC，不表示READY或激活',
    PRIMARY KEY (tenant_id, environment, cell, namespace, generation),
    CONSTRAINT chk_referral_verified_generation CHECK (generation > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='裂变已验证不可变制品，仅支持预热前恢复';
