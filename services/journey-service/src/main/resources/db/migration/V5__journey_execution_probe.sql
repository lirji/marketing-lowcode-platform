CREATE TABLE mk_journey_execution_probe (
    tenant_id VARCHAR(64) NOT NULL COMMENT '可信租户编号',
    environment_name VARCHAR(32) NOT NULL COMMENT '配置中的运行环境',
    cell_id VARCHAR(64) NOT NULL COMMENT '配置中的运行区域',
    namespace_name VARCHAR(128) NOT NULL COMMENT '本站Journey命名空间',
    campaign_id VARCHAR(64) NOT NULL COMMENT '源组合活动编号',
    probe_id VARCHAR(64) NOT NULL COMMENT 'Control签名挑战编号',
    activation_sequence BIGINT NOT NULL COMMENT '本站已接收整体授权序号',
    probe_json TEXT NOT NULL COMMENT '完整不可变签名挑战；入口最大8KiB',
    deployment_fingerprint CHAR(64) NOT NULL COMMENT '登记时可信部署配置的规范SHA256摘要',
    expires_at VARCHAR(40) NOT NULL COMMENT '签名挑战到期时间；UTC九位小数',
    created_by VARCHAR(128) NOT NULL COMMENT '首次登记的可信调用者',
    created_at VARCHAR(40) NOT NULL COMMENT '首次登记时间；UTC九位小数',
    PRIMARY KEY (tenant_id, environment_name, cell_id, namespace_name, campaign_id, probe_id),
    CONSTRAINT fk_journey_probe_directive FOREIGN KEY (tenant_id, environment_name, cell_id, namespace_name, campaign_id, activation_sequence)
        REFERENCES mk_runtime_bundle_directive (tenant_id, environment_name, cell_id, namespace_name, campaign_id, activation_sequence)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin COMMENT 'Journey持久执行挑战；登记不表示投递或整体应用';

CREATE TABLE mk_journey_execution_probe_ack (
    tenant_id VARCHAR(64) NOT NULL COMMENT '可信租户编号',
    environment_name VARCHAR(32) NOT NULL COMMENT '配置中的运行环境',
    cell_id VARCHAR(64) NOT NULL COMMENT '配置中的运行区域',
    namespace_name VARCHAR(128) NOT NULL COMMENT '本站Journey命名空间',
    campaign_id VARCHAR(64) NOT NULL COMMENT '源组合活动编号',
    probe_id VARCHAR(64) NOT NULL COMMENT 'Control签名挑战编号',
    subtask_index INT NOT NULL COMMENT '真实Flink子任务下标；同挑战只计一次',
    ack_json TEXT NOT NULL COMMENT '第一份完整执行签名ACK；入口最大16KiB',
    accepted_at VARCHAR(40) NOT NULL COMMENT '实际持久接受时间；UTC九位小数',
    PRIMARY KEY (tenant_id, environment_name, cell_id, namespace_name, campaign_id, probe_id, subtask_index),
    CONSTRAINT fk_journey_ack_probe FOREIGN KEY (tenant_id, environment_name, cell_id, namespace_name, campaign_id, probe_id) REFERENCES mk_journey_execution_probe (tenant_id, environment_name, cell_id, namespace_name, campaign_id, probe_id),
    CONSTRAINT ck_journey_ack_subtask CHECK (subtask_index >= 0 AND subtask_index < 128)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin COMMENT 'Journey每个真实子任务的不可变第一份执行证据；不按消息条数计覆盖';
