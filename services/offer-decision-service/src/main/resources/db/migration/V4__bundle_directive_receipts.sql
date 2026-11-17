CREATE TABLE mk_runtime_bundle_directive (
    tenant_id VARCHAR(64) NOT NULL COMMENT '可信身份中的租户编号',
    environment_name VARCHAR(32) NOT NULL COMMENT '运行端配置的环境',
    cell_id VARCHAR(64) NOT NULL COMMENT '运行端配置的区域',
    namespace_name VARCHAR(128) NOT NULL COMMENT '本站decision成员的命名空间',
    campaign_id VARCHAR(64) NOT NULL COMMENT '签名组合的活动编号',
    activation_sequence BIGINT NOT NULL COMMENT '控制面活动整体授权序号；接收不代表生效',
    directive_id VARCHAR(64) NOT NULL COMMENT '签名授权编号；同活动运行位置不可复用到另一序号',
    directive_json MEDIUMTEXT NOT NULL COMMENT '完整签名授权原文；应用层最大1MiB',
    recorded_by VARCHAR(128) NOT NULL COMMENT '本次接收命令的可信调用者',
    recorded_at VARCHAR(40) NOT NULL COMMENT '首次持久接收时间；统一UTC九位小数格式',
    PRIMARY KEY (tenant_id, environment_name, cell_id, namespace_name, campaign_id, activation_sequence),
    UNIQUE KEY uk_bundle_directive_id (tenant_id, environment_name, cell_id, namespace_name, campaign_id, directive_id),
    CONSTRAINT ck_bundle_directive_sequence CHECK (activation_sequence > 0)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin COMMENT 'Decision不可变组合授权接收历史；不作为线上应用成功证明';

CREATE TABLE mk_runtime_bundle_head (
    tenant_id VARCHAR(64) NOT NULL COMMENT '可信身份中的租户编号',
    environment_name VARCHAR(32) NOT NULL COMMENT '运行端配置的环境',
    cell_id VARCHAR(64) NOT NULL COMMENT '运行端配置的区域',
    namespace_name VARCHAR(128) NOT NULL COMMENT '本站decision成员的命名空间',
    campaign_id VARCHAR(64) NOT NULL COMMENT '签名组合的活动编号',
    last_sequence BIGINT NULL COMMENT '最新已接收序号；初始化事务内暂空，不表示线上激活',
    updated_at VARCHAR(40) NOT NULL COMMENT '最新接收提交时间；统一UTC九位小数格式',
    PRIMARY KEY (tenant_id, environment_name, cell_id, namespace_name, campaign_id),
    CONSTRAINT fk_bundle_head_directive FOREIGN KEY (tenant_id, environment_name, cell_id, namespace_name, campaign_id, last_sequence)
        REFERENCES mk_runtime_bundle_directive (tenant_id, environment_name, cell_id, namespace_name, campaign_id, activation_sequence)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin COMMENT 'Decision每活动最新已接收组合授权游标；与历史同事务提交';
