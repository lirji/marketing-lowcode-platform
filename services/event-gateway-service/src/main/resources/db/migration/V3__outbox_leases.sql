ALTER TABLE mk_event_outbox
  ADD COLUMN publish_state varchar(16) NOT NULL DEFAULT 'PENDING'
    COMMENT '发布状态：PENDING待领取、SENDING租约内发送、PUBLISHED已发布或DEAD永久失败' AFTER last_error,
  ADD COLUMN lease_owner varchar(128) NULL
    COMMENT '当前发布租约持有者；Kafka I/O期间不持有数据库事务' AFTER publish_state,
  ADD COLUMN lease_until varchar(40) NULL
    COMMENT '发布租约截止时间（ISO-8601字符串，UTC）' AFTER lease_owner,
  ADD COLUMN lease_version bigint NOT NULL DEFAULT 0
    COMMENT '发布租约fencing世代，阻止过期worker回写' AFTER lease_until,
  ADD CONSTRAINT ck_event_outbox_publish_state
    CHECK (publish_state IN ('PENDING','SENDING','PUBLISHED','DEAD')),
  ADD CONSTRAINT ck_event_outbox_lease_version CHECK (lease_version >= 0);

CREATE INDEX ix_event_outbox_claim
  ON mk_event_outbox (publish_state, next_attempt_at, lease_until, created_at);
