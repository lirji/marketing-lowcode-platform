ALTER TABLE mk_event_outbox
  ADD COLUMN depth_bucket_id smallint NOT NULL DEFAULT 0
    COMMENT '深度计数分桶编号（CRC32(outbox_id)对64取模）' AFTER lease_version,
  ADD CONSTRAINT ck_event_outbox_depth_bucket CHECK (depth_bucket_id >= 0 AND depth_bucket_id < 64);

-- V3 引入 publish_state 时存量行都取得默认 PENDING；按既有终态字段做一次前向校正。
UPDATE mk_event_outbox
SET publish_state = CASE
      WHEN published_at IS NOT NULL THEN 'PUBLISHED'
      WHEN dead_lettered_at IS NOT NULL THEN 'DEAD'
      ELSE 'PENDING'
    END,
    depth_bucket_id = MOD(CRC32(outbox_id), 64);

CREATE INDEX ix_event_outbox_oldest_pending
  ON mk_event_outbox (publish_state, created_at);

CREATE TABLE mk_event_outbox_depth (
  scope_type varchar(8) NOT NULL COMMENT '计数范围：GLOBAL全局或TENANT单租户',
  scope_id varchar(64) NOT NULL COMMENT '范围标识；GLOBAL固定为*，TENANT为租户ID',
  bucket_id smallint NOT NULL COMMENT '计数分桶编号；固定为0到63',
  pending_count bigint NOT NULL COMMENT '本分桶尚未发布且未死信的outbox数量',
  updated_at varchar(40) NOT NULL COMMENT '最后一次计数变更时间（ISO-8601字符串，UTC）',
  PRIMARY KEY (scope_type, scope_id, bucket_id),
  CONSTRAINT ck_event_outbox_depth_scope CHECK (scope_type IN ('GLOBAL','TENANT')),
  CONSTRAINT ck_event_outbox_depth_bucket_id CHECK (bucket_id >= 0 AND bucket_id < 64),
  CONSTRAINT ck_event_outbox_depth_nonnegative CHECK (pending_count >= 0)
) COMMENT='Event outbox事务型64路深度计数；为集群共享背压提供有界成本读模型';

-- 存量未完成行分别回填单租户与全局计数；后续增减与 outbox 状态在同一本地事务提交。
INSERT INTO mk_event_outbox_depth(scope_type,scope_id,bucket_id,pending_count,updated_at)
SELECT 'TENANT',tenant_id,depth_bucket_id,COUNT(*),DATE_FORMAT(UTC_TIMESTAMP(6),'%Y-%m-%dT%H:%i:%s.%fZ')
FROM mk_event_outbox
WHERE publish_state IN ('PENDING','SENDING')
GROUP BY tenant_id,depth_bucket_id;

INSERT INTO mk_event_outbox_depth(scope_type,scope_id,bucket_id,pending_count,updated_at)
SELECT 'GLOBAL','*',depth_bucket_id,COUNT(*),DATE_FORMAT(UTC_TIMESTAMP(6),'%Y-%m-%dT%H:%i:%s.%fZ')
FROM mk_event_outbox
WHERE publish_state IN ('PENDING','SENDING')
GROUP BY depth_bucket_id;
