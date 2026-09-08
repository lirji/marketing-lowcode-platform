-- 仅新增首次归因及永久回执，不改V1/V2，不插入生产参数或清理数据。

CREATE TABLE mk_referral_relation (
  tenant_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '租户ID，所有归因与访问隔离首键',
  relation_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '随机关系ID',
  campaign_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '活动ID，好友唯一归因不含规则版本',
  organization_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原参与者冻结组织ID',
  shop_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原参与者冻结店铺ID',
  participant_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '首次有效邀请人的原参与者ID',
  token_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原分享token永久资源ID，不是原token',
  invitee_key char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '好友主体完整HMAC索引，不是普通内容摘要',
  subject_key_version bigint unsigned NOT NULL COMMENT '与持久锚点一致的HMAC密钥版本',
  invitee_cipher varbinary(2048) NOT NULL COMMENT '好友主体受保护密文，禁止存原文',
  encryption_key_id varchar(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '独立于HMAC索引密钥的主体加密key引用',
  definition_id varchar(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '绑定时固定原参与者规则定义',
  definition_version bigint unsigned NOT NULL COMMENT '原参与者固定定义版本',
  generation bigint unsigned NOT NULL COMMENT '原参与者固定发布代次',
  artifact_id varchar(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原参与者固定制品ID',
  policy_hash char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '冻结规则内容摘要',
  bound_at datetime(6) NOT NULL COMMENT '首次有效绑定时间，UTC微秒',
  deadline_at datetime(6) NOT NULL COMMENT '冻结达标窗口与结算截止最小值，UTC微秒',
  consent_version varchar(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '首次接受的已发布条款版本',
  consent_hash char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '首次接受的公开条款内容摘要',
  state varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'BOUND' COMMENT '关系状态，BOUND不表示资格或奖励成功',
  reason_code varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING_EVIDENCE' COMMENT '初始为待权威资格证据，后续不可据此改绑',
  created_at datetime(6) NOT NULL COMMENT '记录创建时间，UTC微秒',
  updated_at datetime(6) NOT NULL COMMENT '记录更新时间，UTC微秒',
  row_version bigint unsigned NOT NULL DEFAULT 1 COMMENT '关系或命令修订，初始为1',
  PRIMARY KEY(tenant_id,relation_id),
  UNIQUE KEY uk_relation_invitee(tenant_id,campaign_id,invitee_key),
  KEY ix_relation_participant(tenant_id,participant_id,state,bound_at,relation_id),
  FOREIGN KEY(tenant_id,participant_id) REFERENCES mk_referral_participant(tenant_id,participant_id),
  FOREIGN KEY(tenant_id,token_id) REFERENCES mk_referral_invite_token(tenant_id,token_id),
  CHECK(subject_key_version>0 AND definition_version>0 AND generation>0),
  CHECK(state IN ('BOUND','PENDING_QUALIFICATION','QUALIFIED','EXPIRED','INVALIDATED','BLOCKED','REVIEW'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='首次有效邀请归因永久关系，后续资格失败不释放或改绑';

CREATE TABLE mk_referral_bind_command (
  tenant_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '租户ID，所有归因与访问隔离首键',
  invitee_key char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '受信好友HMAC作用域',
  idempotency_key varchar(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '绑定请求幂等键',
  request_hash char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '完整业务摘要，包含token摘要条款范围和好友键，不含原token或断言',
  relation_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '首次成功关系引用，仅当前未提交事务内可空',
  created_at datetime(6) NOT NULL COMMENT '记录创建时间，UTC微秒',
  updated_at datetime(6) NOT NULL COMMENT '记录更新时间，UTC微秒',
  row_version bigint unsigned NOT NULL DEFAULT 1 COMMENT '关系或命令修订，初始为1',
  PRIMARY KEY(tenant_id,invitee_key,idempotency_key),
  FOREIGN KEY(tenant_id,relation_id) REFERENCES mk_referral_relation(tenant_id,relation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='首绑永久业务回执，不依赖可淘汰API响应缓存';
