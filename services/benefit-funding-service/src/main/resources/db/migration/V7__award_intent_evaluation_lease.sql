ALTER TABLE mk_award_intent_dedupe
  DROP CHECK ck_award_intent_dedupe_result,
  MODIFY COLUMN result_type varchar(16) NOT NULL
    COMMENT '幂等处理状态：PROCESSING评估中、OUTBOX已受理或BLOCK已拦截',
  ADD COLUMN lease_owner varchar(128) NULL
    COMMENT '首次评估租约持有者；同幂等键只有持有者可调用风控' AFTER result_type,
  ADD COLUMN lease_until varchar(40) NULL
    COMMENT '首次评估租约截止时间（ISO-8601字符串，UTC）' AFTER lease_owner,
  ADD COLUMN lease_version bigint NOT NULL DEFAULT 0
    COMMENT '首次评估租约fencing世代，阻止过期请求持久化结果' AFTER lease_until,
  ADD CONSTRAINT ck_award_intent_dedupe_result
    CHECK (result_type IN ('PROCESSING','OUTBOX','BLOCK')),
  ADD CONSTRAINT ck_award_intent_dedupe_lease_version CHECK (lease_version >= 0);

CREATE INDEX ix_award_intent_dedupe_lease
  ON mk_award_intent_dedupe (result_type, lease_until);
