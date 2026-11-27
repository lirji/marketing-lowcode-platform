CREATE TABLE mk_funding_archive_cutover_grant (
  tenant_id VARCHAR(64) NOT NULL COMMENT '已核准的资金审计租户',
  batch_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '独立批准在线副本移出的精确批次',
  approval_reference VARCHAR(96) NOT NULL COMMENT '业务、保全或合规批准所用的工单引用',
  approved_by VARCHAR(255) NOT NULL COMMENT '独立批准主体',
  reviewed_through DATETIME(6) NOT NULL COMMENT '已完成保留审查的最晚事实时间UTC',
  effective_at DATETIME(3) NOT NULL COMMENT '批准开始时间UTC',
  expires_at DATETIME(3) NOT NULL COMMENT '批准失效时间UTC',
  revoked_at DATETIME(3) NULL COMMENT '审批撤销时间UTC',
  PRIMARY KEY(tenant_id,batch_id),
  CONSTRAINT fk_funding_archive_cutover_batch FOREIGN KEY(tenant_id,batch_id)
    REFERENCES mk_operation_audit_archive_batch(tenant_id,batch_id),
  CONSTRAINT ck_funding_archive_cutover_time CHECK(expires_at>effective_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='资金档案在线证据切换的限时独立工单';

CREATE TABLE mk_funding_archive_hold (
  tenant_id VARCHAR(64) NOT NULL COMMENT '资金审计保全归属租户',
  case_reference VARCHAR(96) NOT NULL COMMENT '独立保全案件引用',
  application_id VARCHAR(64) NULL COMMENT '具体资金申请，整租户保全时为空',
  created_by VARCHAR(255) NOT NULL COMMENT '登记案件的认证主体',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '保全登记时间UTC',
  released_by VARCHAR(255) NULL COMMENT '解除保全的授权人员',
  released_at DATETIME(3) NULL COMMENT '批准解除时间UTC',
  PRIMARY KEY(tenant_id,case_reference),
  KEY ix_funding_archive_hold(tenant_id,released_at,application_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='独立于批次清单的资金审计保全范围';

CREATE TABLE mk_funding_archive_effect_locator (
  tenant_id VARCHAR(64) NOT NULL COMMENT '原始资金审计效果的可信租户',
  effect_id BIGINT NOT NULL COMMENT '原成功结果的永久标识',
  application_id VARCHAR(64) NOT NULL COMMENT '原资金申请引用',
  action_code VARCHAR(64) NOT NULL COMMENT '固定资金动作',
  command_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原命令摘要，支持成功效果的重试关联',
  batch_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '该效果所属的固定版本归档批次',
  PRIMARY KEY(tenant_id,effect_id),
  KEY ix_funding_archive_replay(tenant_id,application_id,action_code,command_hash,effect_id),
  CONSTRAINT fk_funding_archive_effect_batch FOREIGN KEY(tenant_id,batch_id)
    REFERENCES mk_operation_audit_archive_batch(tenant_id,batch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='资金原效果移出后的最小幂等定位记录';

CREATE TABLE mk_funding_archive_cutover_receipt (
  tenant_id VARCHAR(64) NOT NULL COMMENT '已完成在线切换的可信租户',
  batch_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '执行事务移出的固定档案批次',
  approval_reference VARCHAR(96) NOT NULL COMMENT '事务内核验通过的切换审批',
  moved_count SMALLINT UNSIGNED NOT NULL COMMENT '一并移出的原始尝试和确认结果数量',
  moved_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '在线切换事务的确认时间UTC',
  recovery_version VARCHAR(255) NULL COMMENT '备份域外提交凭证版本',
  recovery_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '备份域外提交凭证摘要',
  recovery_bytes INT UNSIGNED NULL COMMENT '提交证明对象的确切字节数',
  PRIMARY KEY(tenant_id,batch_id),
  KEY ix_funding_archive_receipt_pending(tenant_id,recovery_version,batch_id),
  CONSTRAINT fk_funding_archive_receipt_batch FOREIGN KEY(tenant_id,batch_id)
    REFERENCES mk_operation_audit_archive_batch(tenant_id,batch_id),
  CONSTRAINT ck_funding_archive_receipt_count CHECK(moved_count BETWEEN 1 AND 200),
  CONSTRAINT ck_funding_archive_receipt_proof CHECK(
    (recovery_version IS NULL AND recovery_sha256 IS NULL AND recovery_bytes IS NULL)
    OR (recovery_version IS NOT NULL AND recovery_sha256 IS NOT NULL AND recovery_bytes BETWEEN 1 AND 524288)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='资金审计在线切换与补发外部恢复凭证的稳定回执';
