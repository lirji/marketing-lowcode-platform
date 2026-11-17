CREATE TABLE mk_bundle_applied_authorization (
    tenant_id VARCHAR(64) NOT NULL COMMENT '认证租户编号',
    environment_name VARCHAR(32) NOT NULL COMMENT '准入权威所在环境',
    cell_id VARCHAR(64) NOT NULL COMMENT '准入权威所在区域',
    campaign_id VARCHAR(64) NOT NULL COMMENT '完整组合所属活动',
    activation_sequence BIGINT NOT NULL COMMENT '已实际应用的整体授权序号；不能由RECORDED回填',
    directive_id VARCHAR(64) NOT NULL COMMENT '控制面签名授权编号',
    directive_json MEDIUMTEXT NOT NULL COMMENT '实际应用的完整签名授权原文；应用层最大1MiB',
    applied_at VARCHAR(40) NOT NULL COMMENT '实际应用时间；UTC九位小数格式',
    PRIMARY KEY (tenant_id, environment_name, cell_id, campaign_id, activation_sequence),
    UNIQUE KEY uk_bundle_applied_directive (tenant_id, environment_name, cell_id, campaign_id, directive_id),
    CONSTRAINT ck_bundle_applied_sequence CHECK (activation_sequence > 0)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin COMMENT '活动组合实际应用授权历史；本迁移不生成任何已应用事实';

CREATE TABLE mk_bundle_admission_pointer (
    tenant_id VARCHAR(64) NOT NULL COMMENT '认证租户编号',
    environment_name VARCHAR(32) NOT NULL COMMENT '准入权威所在环境',
    cell_id VARCHAR(64) NOT NULL COMMENT '准入权威所在区域',
    campaign_id VARCHAR(64) NOT NULL COMMENT '完整组合所属活动；跨namespace共用指针',
    applied_sequence BIGINT NOT NULL COMMENT '新参与使用的实际应用序号',
    updated_at VARCHAR(40) NOT NULL COMMENT '指针最后应用时间；UTC九位小数格式',
    PRIMARY KEY (tenant_id, environment_name, cell_id, campaign_id),
    CONSTRAINT fk_bundle_admission_applied FOREIGN KEY (tenant_id, environment_name, cell_id, campaign_id, applied_sequence)
        REFERENCES mk_bundle_applied_authorization (tenant_id, environment_name, cell_id, campaign_id, activation_sequence)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin COMMENT '本cell所有新参与共用的实际应用位置；不等同最高接收序号';

CREATE TABLE mk_bundle_participation (
    tenant_id VARCHAR(64) NOT NULL COMMENT '认证租户编号',
    environment_name VARCHAR(32) NOT NULL COMMENT '参与绑定的环境',
    cell_id VARCHAR(64) NOT NULL COMMENT '参与绑定的区域',
    campaign_id VARCHAR(64) NOT NULL COMMENT '参与绑定的活动',
    participation_id VARCHAR(128) NOT NULL COMMENT '可信业务入口提供的稳定参与编号；永久业务幂等键',
    binding_id VARCHAR(64) NOT NULL COMMENT '服务端生成的不可替换绑定编号',
    subject_hash CHAR(64) NOT NULL COMMENT '带租户和用途的主体SHA256摘要；不是匿名化或身份映射',
    activation_sequence BIGINT NOT NULL COMMENT '首次参与固定的整体授权序号',
    participation_json TEXT NOT NULL COMMENT '已签名完整参与事实；应用层最大16KiB，不含明文主体',
    created_by VARCHAR(128) NOT NULL COMMENT '首次准入的可信调用者审计标识',
    created_at VARCHAR(40) NOT NULL COMMENT '首次准入时间；UTC九位小数格式',
    PRIMARY KEY (tenant_id, environment_name, cell_id, campaign_id, participation_id),
    UNIQUE KEY uk_bundle_binding_id (tenant_id, binding_id),
    CONSTRAINT fk_bundle_participation_applied FOREIGN KEY (tenant_id, environment_name, cell_id, campaign_id, activation_sequence)
        REFERENCES mk_bundle_applied_authorization (tenant_id, environment_name, cell_id, campaign_id, activation_sequence)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin COMMENT '一次参与固定一个完整组合的权威事实；不自动清理或重签';
