-- 不修改V1，不迁移共享数据；没有自动清理或真实生产参数。

CREATE TABLE mk_referral_invite_token (
  tenant_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '租户ID，所有身份与命令隔离首键',
  token_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '随机令牌资源ID，不是原token',
  token_hash char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '32字节安全随机token的SHA256摘要，唯一身份索引',
  participant_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '本人参与者ID及原冻结规则引用',
  expires_at datetime(6) NOT NULL COMMENT '冻结政策决定的token截止时间，UTC微秒',
  revoked_at datetime(6) NULL COMMENT '权威撤销时间，空表示未撤销；本切片无撤销写入口',
  token_version bigint unsigned NOT NULL COMMENT '不透明token格式版本，当前为1',
  created_at datetime(6) NOT NULL COMMENT '记录创建时间，UTC微秒',
  updated_at datetime(6) NOT NULL COMMENT '记录更新时间，UTC微秒',
  row_version bigint unsigned NOT NULL DEFAULT 1 COMMENT '行修订，固定事实禁止普通覆盖',
  PRIMARY KEY(tenant_id,token_id),
  UNIQUE KEY uk_invite_token_hash(tenant_id,token_hash),
  KEY ix_invite_token_participant(tenant_id,participant_id,expires_at),
  FOREIGN KEY(tenant_id,participant_id) REFERENCES mk_referral_participant(tenant_id,participant_id),
  CHECK(token_version=1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='256位随机邀请令牌的永久摘要身份，过期或撤销不删除';

CREATE TABLE mk_referral_invite_replay (
  tenant_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '租户ID，所有身份与命令隔离首键',
  subject_key char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '受信本人主体HMAC索引，不是token摘要',
  idempotency_key varchar(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '本人发行幂等键，永久保留请求冲突依据',
  request_hash char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '固定发行业务内容摘要，不含原token或断言',
  token_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '首次成功发行token资源ID，仅未提交事务内允许空',
  token_hash char(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '原token摘要用于解密后校验，不可反推原文',
  token_expires_at datetime(6) NULL COMMENT '原token到期时间，纳入AEAD附加认证数据',
  replay_until datetime(6) NULL COMMENT '原明文可回显截止，显式配置不超过24小时，不代表物理清理已执行',
  response_cipher varbinary(4096) NULL COMMENT '独立KMS/AEAD保护的原token密文，物理保留与清理待隐私治理签收',
  encryption_key_id varchar(128) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '专用token重放保护密钥引用，与主体HMAC密钥分离',
  created_at datetime(6) NOT NULL COMMENT '记录创建时间，UTC微秒',
  updated_at datetime(6) NOT NULL COMMENT '记录更新时间，UTC微秒',
  row_version bigint unsigned NOT NULL DEFAULT 1 COMMENT '行修订，固定事实禁止普通覆盖',
  PRIMARY KEY(tenant_id,subject_key,idempotency_key),
  FOREIGN KEY(tenant_id,token_id) REFERENCES mk_referral_invite_token(tenant_id,token_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='邀请token专用短期密文回执，禁止明文缓存，过窗拒绝回显但不自动清理';
