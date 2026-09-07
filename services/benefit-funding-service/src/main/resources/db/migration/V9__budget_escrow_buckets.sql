CREATE TABLE mk_resource_escrow_bucket (
  tenant_id varchar(64) NOT NULL COMMENT '租户ID（多租户隔离键）',
  resource_key varchar(256) NOT NULL COMMENT '所属预算资源账户键',
  bucket_id smallint NOT NULL COMMENT 'Escrow分桶编号；同一资源内从0连续编号',
  authorized_amount bigint NOT NULL COMMENT '本分桶被授权的额度',
  available_amount bigint NOT NULL COMMENT '本分桶当前可用额度',
  reserved_amount bigint NOT NULL COMMENT '本分桶当前预留额度',
  consumed_amount bigint NOT NULL COMMENT '本分桶当前已核销额度',
  returned_amount bigint NOT NULL COMMENT '本分桶累计已归还额度',
  fencing_epoch bigint NOT NULL COMMENT '与资源账户同步的fencing纪元',
  version_no bigint NOT NULL COMMENT '分桶独立版本号；每次余额变更单调递增',
  state_name varchar(32) NOT NULL COMMENT '分桶状态：ACTIVE、FROZEN或CLOSED',
  updated_at varchar(40) NOT NULL COMMENT '最后更新时间（ISO-8601字符串，UTC）',
  PRIMARY KEY (tenant_id, resource_key, bucket_id),
  CONSTRAINT fk_escrow_bucket_resource FOREIGN KEY (tenant_id, resource_key)
    REFERENCES mk_resource_account(tenant_id, resource_key),
  CONSTRAINT ck_escrow_bucket_id CHECK (bucket_id >= 0),
  CONSTRAINT ck_escrow_bucket_nonnegative CHECK
    (authorized_amount >= 0 AND available_amount >= 0 AND reserved_amount >= 0
      AND consumed_amount >= 0 AND returned_amount >= 0 AND fencing_epoch >= 1 AND version_no >= 0),
  CONSTRAINT ck_escrow_bucket_conservation CHECK
    (authorized_amount = available_amount + reserved_amount + consumed_amount),
  CONSTRAINT ck_escrow_bucket_state CHECK (state_name IN ('ACTIVE','FROZEN','CLOSED'))
) COMMENT='热点预算账户Escrow分桶；余额写入分散到独立行并由总账汇总守恒';

-- 迁移存量预算时把已预留/已核销状态留在0号桶，只把可用余额均匀拆分。
-- 这样存量 reservation 可以无损映射到0号桶，新增预留立即获得16路写扩展。
INSERT INTO mk_resource_escrow_bucket
  (tenant_id,resource_key,bucket_id,authorized_amount,available_amount,reserved_amount,
   consumed_amount,returned_amount,fencing_epoch,version_no,state_name,updated_at)
SELECT account.tenant_id, account.resource_key, bucket.bucket_id,
       (account.available_amount DIV 16)
         + IF(bucket.bucket_id < MOD(account.available_amount,16),1,0)
         + IF(bucket.bucket_id=0,account.reserved_amount+account.consumed_amount,0),
       (account.available_amount DIV 16)
         + IF(bucket.bucket_id < MOD(account.available_amount,16),1,0),
       IF(bucket.bucket_id=0,account.reserved_amount,0),
       IF(bucket.bucket_id=0,account.consumed_amount,0),
       IF(bucket.bucket_id=0,account.returned_amount,0),
       account.fencing_epoch,
       IF(bucket.bucket_id=0,account.version_no,0),
       account.state_name,
       account.updated_at
FROM mk_resource_account account
JOIN (
  SELECT 0 bucket_id UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3
  UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7
  UNION ALL SELECT 8 UNION ALL SELECT 9 UNION ALL SELECT 10 UNION ALL SELECT 11
  UNION ALL SELECT 12 UNION ALL SELECT 13 UNION ALL SELECT 14 UNION ALL SELECT 15
) bucket
WHERE account.resource_type='BUDGET';

CREATE TABLE mk_reservation_escrow_allocation (
  tenant_id varchar(64) NOT NULL COMMENT '租户ID（多租户隔离键）',
  application_id varchar(64) NOT NULL COMMENT '所属优惠申请ID',
  resource_key varchar(256) NOT NULL COMMENT '所属预算资源账户键',
  bucket_id smallint NOT NULL COMMENT '实际承担本次预留的Escrow分桶编号',
  original_amount bigint NOT NULL COMMENT '本分桶初始预留金额',
  reserved_amount bigint NOT NULL COMMENT '本分桶当前预留中金额',
  consumed_amount bigint NOT NULL COMMENT '本分桶已核销金额',
  refunded_amount bigint NOT NULL COMMENT '本分桶已退款金额',
  released_amount bigint NOT NULL COMMENT '本分桶已释放金额',
  reservation_epoch bigint NOT NULL COMMENT '建立预留时的fencing纪元',
  PRIMARY KEY (tenant_id, application_id, resource_key, bucket_id),
  CONSTRAINT fk_escrow_allocation_item FOREIGN KEY (tenant_id, application_id, resource_key)
    REFERENCES mk_reservation_item(tenant_id, application_id, resource_key),
  CONSTRAINT fk_escrow_allocation_bucket FOREIGN KEY (tenant_id, resource_key, bucket_id)
    REFERENCES mk_resource_escrow_bucket(tenant_id, resource_key, bucket_id),
  CONSTRAINT ck_escrow_allocation_nonnegative CHECK
    (original_amount > 0 AND reserved_amount >= 0 AND consumed_amount >= 0
      AND refunded_amount >= 0 AND released_amount >= 0 AND reservation_epoch >= 1),
  CONSTRAINT ck_escrow_allocation_conservation CHECK
    (original_amount = reserved_amount + consumed_amount + refunded_amount + released_amount)
) COMMENT='优惠申请到预算Escrow分桶的持久化分配；结算严格回写原分桶';

-- 存量预留与存量流水都映射到0号桶；迁移后新命令才会按哈希分散。
INSERT INTO mk_reservation_escrow_allocation
  (tenant_id,application_id,resource_key,bucket_id,original_amount,reserved_amount,
   consumed_amount,refunded_amount,released_amount,reservation_epoch)
SELECT item.tenant_id,item.application_id,item.resource_key,0,item.original_amount,item.reserved_amount,
       item.consumed_amount,item.refunded_amount,item.released_amount,item.reservation_epoch
FROM mk_reservation_item item
JOIN mk_resource_account account
  ON account.tenant_id=item.tenant_id AND account.resource_key=item.resource_key
WHERE account.resource_type='BUDGET';

ALTER TABLE mk_funding_ledger
  ADD COLUMN escrow_bucket_id smallint NOT NULL DEFAULT 0 COMMENT '余额落点分桶；非预算资源固定为0' AFTER resource_key;
DROP INDEX uq_ledger_resource_version ON mk_funding_ledger;
CREATE UNIQUE INDEX uq_ledger_resource_bucket_version
  ON mk_funding_ledger(tenant_id,resource_key,escrow_bucket_id,account_version);
CREATE INDEX ix_escrow_bucket_capacity
  ON mk_resource_escrow_bucket(tenant_id,resource_key,state_name,fencing_epoch,available_amount,bucket_id);
