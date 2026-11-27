CREATE TABLE mk_operation_audit_archive_batch (
  tenant_id VARCHAR(64) NOT NULL COMMENT '经过验证的归档租户',
  batch_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '传输与回读使用的稳定批次号',
  resource_id VARCHAR(128) NOT NULL COMMENT '本服务验证通过的业务范围',
  schema_version SMALLINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '归档清单与正文格式版本',
  facts_from DATETIME(6) NOT NULL COMMENT '本批最早关联事实时间UTC',
  facts_until DATETIME(6) NOT NULL COMMENT '本批最晚关联事实时间UTC',
  row_count SMALLINT UNSIGNED NOT NULL COMMENT '固定快照证据行数',
  object_key VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '来源、租户及批次固定的对象键',
  object_version VARCHAR(255) NULL COMMENT '对象存储生成的精确版本',
  sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '压缩后档案完整性摘要',
  payload_bytes INT UNSIGNED NOT NULL COMMENT '实际压缩字节数',
  payload MEDIUMBLOB NULL COMMENT '验证前保留的脱敏快照，验证通过后释放',
  state VARCHAR(24) NOT NULL COMMENT '领取、上传、校验和在线切换状态',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '批次创建时间UTC',
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '状态更新时间UTC',
  pending_tenant VARCHAR(64) GENERATED ALWAYS AS (
    CASE WHEN state IN('CLAIMED','UPLOADED') THEN tenant_id ELSE NULL END
  ) STORED COMMENT '限制同租户待核验批次数，存储故障时保护在线库容量',
  PRIMARY KEY(tenant_id,batch_id),
  UNIQUE KEY uk_mk_archive_pending(pending_tenant),
  UNIQUE KEY uk_mk_archive_snapshot(tenant_id,resource_id,sha256),
  CONSTRAINT ck_mk_archive_counts CHECK (row_count BETWEEN 1 AND 200 AND payload_bytes BETWEEN 1 AND 524288
    AND facts_from<=facts_until),
  CONSTRAINT ck_mk_archive_state CHECK (
    (state='CLAIMED' AND object_version IS NULL AND payload IS NOT NULL)
    OR (state='UPLOADED' AND object_version IS NOT NULL AND payload IS NOT NULL)
    OR (state IN('VERIFIED','ONLINE_PURGED') AND object_version IS NOT NULL AND payload IS NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='发布服务审计归档清单、状态和精确对象版本';

CREATE TABLE mk_operation_audit_archive_grant (
  tenant_id VARCHAR(64) NOT NULL COMMENT '授权工单的租户范围',
  ticket_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '独立审批工单号',
  batch_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '向操作人授权的唯一档案批次',
  actor_id VARCHAR(255) NOT NULL COMMENT '允许恢复的当前认证主体',
  approved_by VARCHAR(255) NOT NULL COMMENT '独立审批身份',
  expires_at DATETIME(3) NOT NULL COMMENT '恢复和读权限最晚有效时间UTC',
  revoked_at DATETIME(3) NULL COMMENT '独立审批撤销时间UTC',
  PRIMARY KEY(tenant_id,ticket_id),
  CONSTRAINT fk_mk_archive_grant_batch FOREIGN KEY(tenant_id,batch_id)
    REFERENCES mk_operation_audit_archive_batch(tenant_id,batch_id),
  CONSTRAINT ck_mk_archive_grant_actor CHECK(actor_id<>approved_by)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='限定发布审计档案单批次的工单授权';

CREATE TABLE mk_operation_audit_archive_restored (
  tenant_id VARCHAR(64) NOT NULL COMMENT '临时查询副本的归属租户',
  ticket_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '允许查阅该副本的有效工单',
  batch_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '实际恢复的精确档案批次',
  payload JSON NOT NULL COMMENT '已按大小、摘要和范围校验的临时证据投影',
  expires_at DATETIME(3) NOT NULL COMMENT '临时证据投影最晚保留时间UTC',
  PRIMARY KEY(tenant_id,ticket_id),
  KEY ix_mk_archive_temp_expiry(tenant_id,expires_at,ticket_id),
  CONSTRAINT fk_mk_archive_restore_grant FOREIGN KEY(tenant_id,ticket_id)
    REFERENCES mk_operation_audit_archive_grant(tenant_id,ticket_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='工单范围内的发布审计归档临时副本';
