-- 仅新增事实接收账本与持久待投影水位；不接真实消息、不写资格或奖励、不初始化生产参数。
CREATE TABLE mk_referral_order_evidence_current (
 tenant_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '认证租户ID，所有订单账本隔离首键',
 source_system varchar(128) COLLATE utf8mb4_0900_bin NOT NULL COMMENT '经认证的订单来源命名空间，精确无填充比较',
 order_id varchar(191) COLLATE utf8mb4_0900_bin NOT NULL COMMENT '来源内永久订单ID，不将主体纳入此身份',
 resource_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '随机内部证据资源ID，用于脱敏审计与Outbox',
 subject_key char(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '原受信主体完整HMAC，仅事务内空占位允许为空',
 subject_key_version bigint unsigned NULL COMMENT '持久索引锚点版本，仅事务内占位允许为空',
 organization_id varchar(64) COLLATE utf8mb4_0900_bin NULL COMMENT '原订单精确组织范围，Scope漂移不能覆盖',
 shop_id varchar(64) COLLATE utf8mb4_0900_bin NULL COMMENT '原订单精确店铺范围，Scope漂移不能覆盖',
 business_revision bigint unsigned NULL COMMENT '加密State中latest的原业务修订，仅事务占位可空',
 state_cipher mediumblob NULL COMMENT '完整State受保护密文，含原始精度时间和首次接收锚点，禁止明文JSON',
 encryption_key_id varchar(128) COLLATE utf8mb4_0900_bin NULL COMMENT '独立于主体索引密钥的证据保护密钥引用',
 state_digest char(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '受保护完整State稳定业务摘要，不用普通hash索引主体',
 quarantined boolean NOT NULL DEFAULT false COMMENT '永久隔离标记，同时由密文AAD认证，禁止自动解除',
 row_version bigint unsigned NOT NULL DEFAULT 0 COMMENT '当前聚合CAS水位，0仅为同事务新建占位不得单独提交',
 created_at datetime(6) NOT NULL COMMENT '创建时间扫描索引，UTC微秒，不替代密文中的原始事实时间',
 updated_at datetime(6) NOT NULL COMMENT '最近接收更新时间，UTC微秒',
 PRIMARY KEY(tenant_id,source_system,order_id),
 UNIQUE KEY uk_evidence_resource(tenant_id,resource_id),
 KEY ix_evidence_subject(tenant_id,subject_key,organization_id,shop_id),
 CHECK((row_version=0 AND state_cipher IS NULL AND subject_key IS NULL AND subject_key_version IS NULL AND organization_id IS NULL AND shop_id IS NULL AND business_revision IS NULL AND encryption_key_id IS NULL AND state_digest IS NULL AND quarantined=false)
    OR (row_version>0 AND state_cipher IS NOT NULL AND subject_key IS NOT NULL AND subject_key_version IS NOT NULL AND subject_key_version>0 AND organization_id IS NOT NULL AND shop_id IS NOT NULL AND business_revision IS NOT NULL AND business_revision>0 AND encryption_key_id IS NOT NULL AND state_digest IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='单订单累计证据当前聚合，主体漂移及非法高修订永久隔离';

CREATE TABLE mk_referral_order_evidence_history (
 tenant_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '认证租户ID，历史账本隔离首键',
 source_system varchar(128) COLLATE utf8mb4_0900_bin NOT NULL COMMENT '经认证订单来源命名空间',
 order_id varchar(191) COLLATE utf8mb4_0900_bin NOT NULL COMMENT '来源内永久订单ID，精确比较',
 business_revision bigint unsigned NOT NULL COMMENT '永久业务修订唯一键，不得按eventId替代',
 subject_key char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '该修订的受信主体HMAC，用于AAD，不覆盖原订单身份',
 subject_key_version bigint unsigned NOT NULL COMMENT '该修订受信索引密钥版本',
 organization_id varchar(64) COLLATE utf8mb4_0900_bin NOT NULL COMMENT '该修订精确组织范围，冲突历史也保留完整语义',
 shop_id varchar(64) COLLATE utf8mb4_0900_bin NOT NULL COMMENT '该修订精确店铺范围',
 snapshot_cipher mediumblob NOT NULL COMMENT '该业务修订完整Snapshot受保护密文，禁止保存canonicalSubject明文',
 encryption_key_id varchar(128) COLLATE utf8mb4_0900_bin NOT NULL COMMENT '历史密文独立保护密钥引用',
 business_digest char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '完整规范业务内容受保护稳定摘要，重试运输时间不参与',
 first_received_at datetime(6) NOT NULL COMMENT '首次接收扫描索引，精确原时间另存秒纳秒且不被重试覆盖',
 first_received_seconds bigint NOT NULL COMMENT '首次接收原始Instant的epoch秒，保持完整时间精度',
 first_received_nanos int unsigned NOT NULL COMMENT '首次接收原始Instant的纳秒部分，禁止用重试时间覆盖',
 created_at datetime(6) NOT NULL COMMENT '首次历史入账时间，UTC微秒',
 PRIMARY KEY(tenant_id,source_system,order_id,business_revision),
 FOREIGN KEY(tenant_id,source_system,order_id) REFERENCES mk_referral_order_evidence_current(tenant_id,source_system,order_id),
 CHECK(business_revision>0 AND subject_key_version>0 AND first_received_nanos<1000000000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='累计证据每个业务修订完整永久历史，不可覆盖历史或丢失首次接收锚点';

CREATE TABLE mk_referral_evidence_inbox (
 tenant_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '认证租户ID，事件隔离首键',
 issuer varchar(128) COLLATE utf8mb4_0900_bin NOT NULL COMMENT '经真实来源Port认证的事件发行者',
 source_system varchar(128) COLLATE utf8mb4_0900_bin NOT NULL COMMENT '经认证订单来源命名空间，事件ID在此范围唯一',
 event_id varchar(128) COLLATE utf8mb4_0900_bin NOT NULL COMMENT '来源事件ID，永久回执键',
 business_digest char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原事件完整规范业务受保护摘要，异内容不得覆盖',
 order_id varchar(191) COLLATE utf8mb4_0900_bin NOT NULL COMMENT '首次接受事件的原订单ID，跨订单冲突仍隔离此原资源',
 result_resource_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '原成功证据资源引用，仅当前未提交占位可空',
 result_outcome varchar(32) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '原合并接收结果，不代表资格或奖励，仅未提交占位可空',
 result_reason varchar(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '原接收原因码，重放不按当前状态改写',
 result_state_version bigint unsigned NULL COMMENT '原成功时聚合水位，永久回放保持原值',
 created_at datetime(6) NOT NULL COMMENT '事件首次接收回执创建时间，UTC微秒',
 updated_at datetime(6) NOT NULL COMMENT '原事件回执完成时间，UTC微秒',
 PRIMARY KEY(tenant_id,issuer,source_system,event_id),
 FOREIGN KEY(tenant_id,result_resource_id) REFERENCES mk_referral_order_evidence_current(tenant_id,resource_id),
 CHECK((result_resource_id IS NULL AND result_outcome IS NULL AND result_reason IS NULL AND result_state_version IS NULL)
    OR (result_resource_id IS NOT NULL AND result_outcome IS NOT NULL AND result_reason IS NOT NULL AND result_state_version IS NOT NULL AND result_state_version>0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='认证来源事件永久Inbox回执，异内容冲突保留原成功并追加审计';

CREATE TABLE mk_referral_evidence_fanout (
 tenant_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '认证租户ID，待投影任务隔离首键',
 resource_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '永久订单证据资源ID，不以新事件主体覆盖已有依赖',
 desired_version bigint unsigned NOT NULL COMMENT '必须投影的当前证据最高水位，变化后持久推进',
 reason varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '最近重评原因码，不含原主体或原载荷',
 status varchar(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING' COMMENT '持久扫描状态，此切片不自动启动处理Worker',
 cursor_relation_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '后续单关系扫描游标，新目标水位时重置以覆盖所有依赖',
 lease_owner varchar(128) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '后续扫描租约持有者，默认无人领取',
 lease_until datetime(6) NULL COMMENT '后续短租约截止，UTC扫描索引',
 lease_fence bigint unsigned NOT NULL DEFAULT 1 COMMENT '任务fencing版本，新证据使旧worker失效',
 row_version bigint unsigned NOT NULL DEFAULT 1 COMMENT '持久任务修订，旧完成不能抹掉新目标水位',
 created_at datetime(6) NOT NULL COMMENT '首次待投影任务创建时间，UTC微秒',
 updated_at datetime(6) NOT NULL COMMENT '待投影目标最近更新时间，UTC微秒',
 PRIMARY KEY(tenant_id,resource_id),
 KEY ix_fanout_scan(tenant_id,status,updated_at,resource_id),
 FOREIGN KEY(tenant_id,resource_id) REFERENCES mk_referral_order_evidence_current(tenant_id,resource_id),
 CHECK(desired_version>0 AND lease_fence>0 AND row_version>0),
 CHECK(status IN ('PENDING','PROCESSING','DONE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='单订单持久重评扫描水位，事实接收与资格投影分开各自原子，不发奖';
