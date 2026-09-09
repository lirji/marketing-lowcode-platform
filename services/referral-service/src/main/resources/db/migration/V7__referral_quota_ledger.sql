-- 所有配额变更与奖励和Outbox同事务；不创建Redis权威计数。

CREATE TABLE mk_referral_quota_account (
  tenant_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '租户隔离及业务主键首列',
  campaign_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '固定活动ID',
  rule_id varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '稳定规则ID，精确区分大小写与尾随空格',
  organization_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '奖励所属组织权限范围',
  shop_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '奖励所属门店权限范围',
  rule_json json NOT NULL COMMENT '已冻结规则原文和额度，不允许请求自报上限',
  quota_limit bigint unsigned NOT NULL COMMENT '活动规则总数量名额，非资金',
  bucket_count int unsigned NOT NULL COMMENT '显式初始化桶数，无生产默认值',
  created_at datetime(6) NOT NULL COMMENT '创建时间UTC微秒',
  updated_at datetime(6) NOT NULL COMMENT '更新时间UTC微秒',
  PRIMARY KEY(tenant_id,campaign_id,rule_id),
  CHECK(quota_limit>0 AND bucket_count>0 AND bucket_count<=1024)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='活动规则冷路径配额配置，全量初始分桶原子提交，热路径不更新总计';

CREATE TABLE mk_referral_quota_bucket (
  tenant_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '租户隔离及业务主键首列',
  campaign_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '固定活动ID',
  rule_id varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '稳定规则ID，精确区分大小写与尾随空格',
  bucket_id int unsigned NOT NULL COMMENT '活动规则下的稳定桶号',
  allocated bigint unsigned NOT NULL COMMENT '分配到桶的总名额，单位份',
  available bigint unsigned NOT NULL COMMENT '尚未预占的空闲名额',
  reserved bigint unsigned NOT NULL DEFAULT 0 COMMENT '未知或待发奖励占用，不能超时释放',
  consumed bigint unsigned NOT NULL DEFAULT 0 COMMENT '已确认成功且按政策保留的历史名额',
  fencing_epoch bigint unsigned NOT NULL DEFAULT 1 COMMENT '调拨栅栏代次，每次调拨递增',
  row_version bigint unsigned NOT NULL DEFAULT 1 COMMENT '每次余额变化递增CAS版本',
  created_at datetime(6) NOT NULL COMMENT '创建时间UTC微秒',
  updated_at datetime(6) NOT NULL COMMENT '更新时间UTC微秒',
  PRIMARY KEY(tenant_id,campaign_id,rule_id,bucket_id),
  CHECK(allocated=available+reserved+consumed AND fencing_epoch>0 AND row_version>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数量配额热路径桶，available加reserved加consumed精确守恒';

CREATE TABLE mk_referral_subject_quota (
  tenant_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '租户隔离及业务主键首列',
  campaign_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '固定活动ID',
  rule_id varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '稳定规则ID，精确区分大小写与尾随空格',
  beneficiary_key char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '规范受益人HMAC，整个活动规则跨桶共用限额',
  quota_limit bigint unsigned NOT NULL COMMENT '冻结个人领取上限，单位份',
  reserved_count bigint unsigned NOT NULL DEFAULT 0 COMMENT '待发及未知占用份数',
  consumed_count bigint unsigned NOT NULL DEFAULT 0 COMMENT '真实成功消耗份数',
  row_version bigint unsigned NOT NULL DEFAULT 1 COMMENT '个人限额CAS版本',
  created_at datetime(6) NOT NULL COMMENT '创建时间UTC微秒',
  updated_at datetime(6) NOT NULL COMMENT '更新时间UTC微秒',
  PRIMARY KEY(tenant_id,campaign_id,rule_id,beneficiary_key),
  CHECK(quota_limit>0 AND reserved_count+consumed_count<=quota_limit AND row_version>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='受益人活动规则全局限领，不按配额桶或请求重置';

CREATE TABLE mk_referral_quota_reservation (
  tenant_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '租户隔离及业务主键首列',
  reward_id char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '永久奖励身份，已释放也不得删除重占',
  campaign_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '固定活动ID',
  rule_id varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT '稳定规则ID，精确区分大小写与尾随空格',
  bucket_id int unsigned NOT NULL COMMENT '首次固定预占桶号',
  participant_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '固定参与者引用',
  beneficiary_key char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '固定受益人HMAC用于个人限额核对',
  created_epoch bigint unsigned NOT NULL COMMENT '首次预占桶代次，调拨后仍保留',
  state varchar(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'RESERVED' COMMENT 'RESERVED预占、CONSUMED成功、RELEASED可靠终态释放',
  reversal_policy varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'RETAIN_CONSUMED' COMMENT '冻结追回政策，默认保留已消费名额',
  row_version bigint unsigned NOT NULL DEFAULT 1 COMMENT '预占终态CAS修订',
  created_at datetime(6) NOT NULL COMMENT '创建时间UTC微秒',
  updated_at datetime(6) NOT NULL COMMENT '更新时间UTC微秒',
  PRIMARY KEY(tenant_id,reward_id),
  KEY ix_quota_reservation_bucket(tenant_id,campaign_id,rule_id,bucket_id,state,reward_id),
  FOREIGN KEY(tenant_id,reward_id) REFERENCES mk_referral_reward(tenant_id,reward_id),
  CHECK(state IN ('RESERVED','CONSUMED','RELEASED')),
  CHECK(reversal_policy='RETAIN_CONSUMED' AND created_epoch>0 AND row_version>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='每份永久奖励唯一数量预占账本，202未知租约到期均不得释放';
