-- marketing_compiler：为已存在的表与字段补充中文注释（Flyway 前向迁移，幂等 ALTER）
-- 说明：本文件仅添加 COMMENT，不改变任何列的类型/可空/默认值。

-- ---- mk_compiled_artifact ----
ALTER TABLE mk_compiled_artifact COMMENT = '编译产物表（由活动定义编译出的可执行规则制品）';
ALTER TABLE mk_compiled_artifact MODIFY COLUMN tenant_id varchar(64) NOT NULL COMMENT '租户ID（多租户隔离键，联合主键首段）';
ALTER TABLE mk_compiled_artifact MODIFY COLUMN artifact_id varchar(128) NOT NULL COMMENT '编译产物ID';
ALTER TABLE mk_compiled_artifact MODIFY COLUMN definition_id varchar(128) NOT NULL COMMENT '定义ID';
ALTER TABLE mk_compiled_artifact MODIFY COLUMN definition_version bigint NOT NULL COMMENT '定义版本号';
ALTER TABLE mk_compiled_artifact MODIFY COLUMN type_name varchar(64) NOT NULL COMMENT '产物类型名';
ALTER TABLE mk_compiled_artifact MODIFY COLUMN abi varchar(128) NOT NULL COMMENT '产物 ABI 版本';
ALTER TABLE mk_compiled_artifact MODIFY COLUMN payload mediumblob NOT NULL COMMENT '二进制负载';
ALTER TABLE mk_compiled_artifact MODIFY COLUMN checksum varchar(80) NOT NULL COMMENT '内容校验和';
ALTER TABLE mk_compiled_artifact MODIFY COLUMN source_digest varchar(80) NOT NULL COMMENT '源定义摘要（编译输入指纹）';
ALTER TABLE mk_compiled_artifact MODIFY COLUMN signature_key_id varchar(128) NOT NULL COMMENT '签名密钥ID';
ALTER TABLE mk_compiled_artifact MODIFY COLUMN signature_value varchar(256) NOT NULL COMMENT '签名值';
ALTER TABLE mk_compiled_artifact MODIFY COLUMN metadata_json text NOT NULL COMMENT '编译元数据（JSON）';
ALTER TABLE mk_compiled_artifact MODIFY COLUMN compiled_at varchar(40) NOT NULL COMMENT '编译完成时间（ISO-8601 字符串，UTC）';
