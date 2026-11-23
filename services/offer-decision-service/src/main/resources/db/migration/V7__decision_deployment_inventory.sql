CREATE TABLE mk_decision_deployment_head (
    environment_name VARCHAR(32) NOT NULL COMMENT '部署环境',
    cell_id VARCHAR(64) NOT NULL COMMENT '流量单元',
    namespace_name VARCHAR(128) NOT NULL COMMENT 'Decision 运行命名空间',
    deployment_sequence BIGINT NOT NULL DEFAULT 0 COMMENT '该物理位置已登记序号，零为事务内初始值',
    content_digest CHAR(71) NOT NULL DEFAULT '' COMMENT '当前签名清单的规范内容摘要',
    PRIMARY KEY (environment_name, cell_id, namespace_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='Decision 接流量部署清单的串行登记位置';

CREATE TABLE mk_decision_deployment_inventory (
    environment_name VARCHAR(32) NOT NULL COMMENT '部署环境',
    cell_id VARCHAR(64) NOT NULL COMMENT '流量单元',
    namespace_name VARCHAR(128) NOT NULL COMMENT 'Decision 运行命名空间',
    deployment_sequence BIGINT NOT NULL COMMENT '部署流水线单调序号',
    content_digest CHAR(71) NOT NULL COMMENT '清单规范内容的 SHA-256 摘要',
    inventory_json MEDIUMTEXT NOT NULL COMMENT '包含部署方签名与完整接流量副本集合的不可变清单',
    recorded_by VARCHAR(128) NOT NULL COMMENT '已认证的流水线调用身份',
    recorded_at VARCHAR(32) NOT NULL COMMENT '首次登记 UTC 时间',
    PRIMARY KEY (environment_name, cell_id, namespace_name, deployment_sequence),
    FOREIGN KEY (environment_name, cell_id, namespace_name)
        REFERENCES mk_decision_deployment_head (environment_name, cell_id, namespace_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='Decision 部署流水线签名副本清单历史';
