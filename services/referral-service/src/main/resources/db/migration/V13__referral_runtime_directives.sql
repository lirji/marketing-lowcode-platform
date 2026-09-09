CREATE TABLE mk_referral_runtime_cursor (
    tenant_id VARCHAR(64) NOT NULL COMMENT '已认证租户',
    stream_kind VARCHAR(16) NOT NULL COMMENT 'ACTIVATION或KILL指令流',
    environment VARCHAR(64) NOT NULL COMMENT '激活环境，熔断流固定空值',
    cell VARCHAR(64) NOT NULL COMMENT '激活部署单元，熔断流固定空值',
    namespace VARCHAR(128) NOT NULL COMMENT '签名命名空间',
    sequence_no BIGINT NOT NULL COMMENT '单调序号，零表示尚无可信指令',
    directive_json MEDIUMTEXT NULL COMMENT '当前完整签名指令，恢复时重新验签',
    PRIMARY KEY (tenant_id,stream_kind,environment,cell,namespace),
    CONSTRAINT chk_referral_runtime_cursor CHECK (sequence_no >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='裂变本地运行指令游标，不代表实际参与许可';

CREATE TABLE mk_referral_runtime_directive (
    tenant_id VARCHAR(64) NOT NULL COMMENT '已认证租户',
    stream_kind VARCHAR(16) NOT NULL COMMENT 'ACTIVATION或KILL指令流',
    environment VARCHAR(64) NOT NULL COMMENT '激活环境，熔断流固定空值',
    cell VARCHAR(64) NOT NULL COMMENT '激活部署单元，熔断流固定空值',
    namespace VARCHAR(128) NOT NULL COMMENT '签名命名空间',
    sequence_no BIGINT NOT NULL COMMENT '不可变指令序号',
    directive_json MEDIUMTEXT NOT NULL COMMENT '完整签名指令原文',
    applied_at DATETIME(6) NOT NULL COMMENT '本地首次应用时间UTC，不刷新签名有效期',
    PRIMARY KEY (tenant_id,stream_kind,environment,cell,namespace,sequence_no),
    CONSTRAINT chk_referral_runtime_directive CHECK (sequence_no > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='裂变运行指令不可变审计，与游标同事务提交';
