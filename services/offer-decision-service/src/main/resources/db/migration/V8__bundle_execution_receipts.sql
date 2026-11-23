CREATE TABLE mk_decision_execution_receipt (
    tenant_id VARCHAR(64) NOT NULL COMMENT '认证租户',
    environment_name VARCHAR(32) NOT NULL COMMENT '物理环境',
    cell_id VARCHAR(64) NOT NULL COMMENT '准入区域',
    namespace_name VARCHAR(128) NOT NULL COMMENT '执行实例的物理命名空间',
    campaign_id VARCHAR(64) NOT NULL COMMENT '授权活动',
    activation_sequence BIGINT NOT NULL COMMENT '已接收授权序号',
    deployment_sequence BIGINT NOT NULL COMMENT '探测时的流水线部署序号',
    instance_id VARCHAR(128) NOT NULL COMMENT '部署清单中的不可变实例身份',
    receipt_json TEXT NOT NULL COMMENT '该副本对本授权和部署的专属执行签名',
    PRIMARY KEY (tenant_id,environment_name,cell_id,namespace_name,campaign_id,activation_sequence,deployment_sequence,instance_id),
    FOREIGN KEY (tenant_id,environment_name,cell_id,namespace_name,campaign_id,activation_sequence)
        REFERENCES mk_runtime_bundle_directive (tenant_id,environment_name,cell_id,namespace_name,campaign_id,activation_sequence),
    FOREIGN KEY (environment_name,cell_id,namespace_name,deployment_sequence)
        REFERENCES mk_decision_deployment_inventory (environment_name,cell_id,namespace_name,deployment_sequence)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin COMMENT '按部署版本及运行实例去重的组合执行证据';

ALTER TABLE mk_runtime_slot
    ADD COLUMN bundle_applied BOOLEAN NOT NULL DEFAULT FALSE COMMENT '本站已有真实组合生效，拒绝无凭证的旧报价';

CREATE TABLE mk_bundle_application_lock (
    tenant_id VARCHAR(64) NOT NULL COMMENT '租户',
    environment_name VARCHAR(32) NOT NULL COMMENT '准入环境',
    cell_id VARCHAR(64) NOT NULL COMMENT '准入区域',
    campaign_id VARCHAR(64) NOT NULL COMMENT '同活动全部命名空间共用一个首次应用仲裁位置',
    PRIMARY KEY (tenant_id,environment_name,cell_id,campaign_id)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin COMMENT '整体应用事务锁，空行没有已生效语义';

ALTER TABLE mk_bundle_applied_authorization
    ADD COLUMN execution_evidence MEDIUMTEXT NULL COMMENT '本次应用实际验证的各 Decision 副本与 Journey 子任务签名回执；旧记录无回填';
