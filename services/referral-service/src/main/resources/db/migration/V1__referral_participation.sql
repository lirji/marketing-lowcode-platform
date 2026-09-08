-- 仅新建独立referral逻辑库内的本切片表，不修改其他服务或共享数据。

CREATE TABLE mk_referral_participant (
  tenant_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '租户ID，所有业务唯一性与访问隔离的首键',
  participant_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '随机参与ID',
  campaign_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '活动ID，跨定义版本唯一参与',
  organization_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '权威许可固定的组织ID',
  shop_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '权威许可固定的店铺ID',
  subject_key char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '受信主体HMAC-SHA256完整索引，非普通摘要',
  subject_key_version bigint unsigned NOT NULL COMMENT '固定HMAC索引密钥版本，轮换需双索引迁移',
  subject_cipher varbinary(2048) NOT NULL COMMENT '主体加密密文，禁止存放主体原文',
  encryption_key_id varchar(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '独立于HMAC索引密钥的加密密钥引用',
  definition_id varchar(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '首次加入固定的规则定义ID',
  definition_version bigint unsigned NOT NULL COMMENT '首次加入固定的定义版本',
  generation bigint unsigned NOT NULL COMMENT '首次加入固定的发布代次',
  artifact_id varchar(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '首次加入固定的规则制品ID',
  policy_hash char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '冻结规则内容SHA256摘要，不是主体索引',
  route_epoch bigint unsigned NOT NULL COMMENT '首次加入固定的cell路由代次',
  state varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'ACTIVE' COMMENT '参与状态，ACTIVE活动中、PAUSED暂停、CLOSED关闭',
  row_version bigint unsigned NOT NULL DEFAULT 1 COMMENT '参与者聚合修订，初始为1',
  created_at datetime(6) NOT NULL COMMENT '记录创建时间，UTC微秒',
  updated_at datetime(6) NOT NULL COMMENT '记录更新时间，UTC微秒',
  PRIMARY KEY(tenant_id,participant_id),
  UNIQUE KEY uk_participant_subject(tenant_id,campaign_id,subject_key),
  KEY ix_participant_campaign(tenant_id,campaign_id,created_at,participant_id),
  CHECK (state IN ('ACTIVE','PAUSED','CLOSED')),
  CHECK (subject_key_version>0 AND definition_version>0 AND generation>0 AND route_epoch>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='邀请人永久参与及固定发布版本，不随发布升级重写';

CREATE TABLE mk_referral_subject_role (
  tenant_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '租户ID，所有业务唯一性与访问隔离的首键',
  campaign_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '活动ID，互斥范围不包含定义版本',
  subject_key char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '规范主体完整HMAC索引',
  subject_key_version bigint unsigned NOT NULL COMMENT '固定主体HMAC密钥版本',
  role varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'INVITER邀请人或INVITEE被邀请人，不允许自动转换',
  participant_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '邀请人参与ID，首次同事务关联',
  relation_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '被邀请关系ID，本切片不创建关系',
  row_version bigint unsigned NOT NULL DEFAULT 1 COMMENT '角色行版本',
  created_at datetime(6) NOT NULL COMMENT '记录创建时间，UTC微秒',
  updated_at datetime(6) NOT NULL COMMENT '记录更新时间，UTC微秒',
  PRIMARY KEY(tenant_id,campaign_id,subject_key),
  UNIQUE KEY uk_role_participant(tenant_id,participant_id),
  CHECK (role IN ('INVITER','INVITEE')),
  CHECK (subject_key_version>0),
  FOREIGN KEY (tenant_id,participant_id) REFERENCES mk_referral_participant(tenant_id,participant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='同活动主体角色互斥及稳定并发锁点';

CREATE TABLE mk_referral_join_command (
  tenant_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '租户ID，所有业务唯一性与访问隔离的首键',
  subject_key char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '认证主体HMAC索引，隔离不同用户请求键',
  idempotency_key varchar(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '加入请求幂等键，不随响应缓存过期删除',
  request_hash char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '规范加入业务内容摘要，不包含断言或主体原文',
  participant_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '首次成功的永久参与者引用，空值只允许在当前未提交事务',
  row_version bigint unsigned NOT NULL DEFAULT 1 COMMENT '命令状态修订，完成后不再改写',
  created_at datetime(6) NOT NULL COMMENT '记录创建时间，UTC微秒',
  updated_at datetime(6) NOT NULL COMMENT '记录更新时间，UTC微秒',
  PRIMARY KEY(tenant_id,subject_key,idempotency_key),
  FOREIGN KEY (tenant_id,participant_id) REFERENCES mk_referral_participant(tenant_id,participant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='加入活动永久业务回执，区别于可淘汰API响应缓存';

CREATE TABLE mk_referral_audit (
  tenant_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '租户ID，所有业务唯一性与访问隔离的首键',
  audit_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '随机审计ID',
  actor_id varchar(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '已认证机器或操作人ID，不是canonicalSubject',
  action varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '领域操作代码',
  resource_ref varchar(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '被操作参与者等资源引用',
  reason varchar(1000) NOT NULL COMMENT '脱敏操作原因，不保存外部原始载荷',
  before_hash char(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '操作前内容摘要，首次创建为空',
  after_hash char(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '操作后内容摘要',
  trace_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '受校验链路引用',
  occurred_at datetime(6) NOT NULL COMMENT '领域操作时间，UTC微秒',
  created_at datetime(6) NOT NULL COMMENT '记录创建时间，UTC微秒',
  updated_at datetime(6) NOT NULL COMMENT '记录更新时间，UTC微秒',
  PRIMARY KEY(tenant_id,audit_id),
  KEY ix_audit_resource(tenant_id,resource_ref,occurred_at,audit_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='裂变领域追加式审计，应用无更新或删除接口';

CREATE TABLE mk_referral_outbox (
  tenant_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '租户ID，所有业务唯一性与访问隔离的首键',
  event_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '随机不可变事件ID',
  aggregate_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '参与者等聚合ID',
  aggregate_revision bigint unsigned NOT NULL COMMENT '聚合递增事件修订',
  event_type varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '内部事实类型，外部消费者合同需另行签收',
  topic varchar(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '受控目标主题，本切片仅保留内部意图',
  partition_key varchar(256) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '租户与聚合组成的分区键',
  payload json NOT NULL COMMENT '不含主体或断言的版本化事件内容',
  payload_hash char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '事件内容SHA256摘要',
  state varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'READY' COMMENT 'READY待发、LEASED租用、SENT已发、DEAD待处置',
  attempts int unsigned NOT NULL DEFAULT 0 COMMENT '投递尝试次数',
  next_retry_at datetime(6) NOT NULL COMMENT '最早重试时间，UTC微秒',
  lease_owner varchar(128) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '投递租约持有人',
  lease_until datetime(6) NULL COMMENT '租约截止时间，UTC微秒',
  lease_version bigint unsigned NOT NULL DEFAULT 0 COMMENT '租约栅栏版本',
  published_at datetime(6) NULL COMMENT '投递成功确认时间，UTC微秒',
  last_error varchar(1000) NOT NULL DEFAULT '' COMMENT '脱敏投递错误，禁止保存主体和外部载荷',
  row_version bigint unsigned NOT NULL DEFAULT 1 COMMENT '发送任务修订',
  created_at datetime(6) NOT NULL COMMENT '记录创建时间，UTC微秒',
  updated_at datetime(6) NOT NULL COMMENT '记录更新时间，UTC微秒',
  PRIMARY KEY(tenant_id,event_id),
  UNIQUE KEY uk_outbox_revision(tenant_id,aggregate_id,event_type,aggregate_revision),
  KEY ix_outbox_ready(tenant_id,state,next_retry_at,event_id),
  CHECK (state IN ('READY','LEASED','SENT','DEAD'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='裂变同事务可靠发送意图，本切片不启动投递';

CREATE TABLE mk_referral_subject_index_anchor (
  tenant_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '租户ID，永久主体索引版本锚点隔离首键',
  subject_key_version bigint unsigned NOT NULL COMMENT '受控初始化固定的HMAC索引版本，未经双索引回填迁移不得变更',
  created_at datetime(6) NOT NULL COMMENT '锚点经受控初始化的时间，UTC微秒',
  updated_at datetime(6) NOT NULL COMMENT '最后一次经治理授权迁移的时间，UTC微秒',
  PRIMARY KEY(tenant_id),
  CHECK (subject_key_version>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='主体HMAC版本永久锚点；应用仅共享锁读取，无创建更新入口，缺失时默认拒绝';
