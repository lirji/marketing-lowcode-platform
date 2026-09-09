-- 终态历史/Inbox永久保留，成功证明不得被退款追回清除。
ALTER TABLE mk_referral_quota_reservation
 ADD COLUMN terminal_fact varchar(32) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'SUCCEEDED、CONFIRMED_NOT_ISSUED、CANCELLED_BEFORE_ISSUE或REVERSED_AFTER_ISSUE的可信依据',
 ADD COLUMN terminal_digest varchar(71) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '永久终态业务证据摘要，重传版本变化不能重置',
 ADD COLUMN success_digest varchar(71) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '首次成功永久证据摘要，追回之后保留供原成功重放';

-- V7–V9唯一已实现的RELEASED来源是同事务本地未发送取消；仅对仍满足该证据链的行补齐证明。
-- 不推断CONSUMED或已授权/已提交行的外部终态，异常历史行保持拒绝，交人工核验。
UPDATE mk_referral_quota_reservation q JOIN mk_referral_reward r
 ON r.tenant_id=q.tenant_id AND r.reward_id=q.reward_id
 SET q.terminal_fact='CANCELLED_BEFORE_ISSUE',q.terminal_digest=CONCAT('sha256:',SHA2(CONCAT('local-cancel:',q.reward_id),256))
 WHERE q.state='RELEASED' AND q.terminal_fact IS NULL AND r.entitlement_state='INVALIDATED'
 AND r.authorization_state='NONE' AND r.delivery_state='NOT_SUBMITTED' AND r.quota_state='RELEASED';

CREATE TABLE mk_referral_fulfillment_current (
 tenant_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '租户隔离首键',
 reward_id char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '永久奖励身份',
 provider_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '已认证且首次固定的权益供应方',
 provider_revision bigint unsigned NOT NULL COMMENT '供应方当前最高累计业务修订',
 delivery_state varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'UNKNOWN、ACCEPTED、SUCCEEDED、FAILED_FINAL的真实履约状态',
 compensation_state varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'NONE、PENDING、CANCELLED、REVERSED、MANUAL_REVIEW，与成功状态正交',
 business_digest varchar(71) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '本次完整规范累计快照摘要',
 terminal_digest varchar(71) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '永久未发放或追回证据摘要，旧版本也不能提交矛盾终态',
 success_digest varchar(71) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '首次成功永久摘要，追回之后不清除',
 created_at datetime(6) NOT NULL COMMENT '首次记录UTC微秒',
 updated_at datetime(6) NOT NULL COMMENT '最新可信快照提交UTC微秒',
 PRIMARY KEY(tenant_id,reward_id),
 FOREIGN KEY(tenant_id,reward_id) REFERENCES mk_referral_reward(tenant_id,reward_id),
 CHECK(provider_revision>0),
 CHECK(delivery_state IN ('UNKNOWN','ACCEPTED','SUCCEEDED','FAILED_FINAL')),
 CHECK(compensation_state IN ('NONE','PENDING','CANCELLED','REVERSED','MANUAL_REVIEW'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='奖励当前可信履约及补偿投影，不根据HTTP受理推断到账';

CREATE TABLE mk_referral_fulfillment_history (
 tenant_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '租户隔离首键',
 reward_id char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '永久奖励身份',
 provider_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '已认证供应方来源命名空间',
 provider_revision bigint unsigned NOT NULL COMMENT '不可覆盖的业务修订',
 business_digest varchar(71) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '完整业务快照稳定摘要，同修订异内容拒绝',
 created_at datetime(6) NOT NULL COMMENT '首次接收UTC微秒',
 updated_at datetime(6) NOT NULL COMMENT '首次接收UTC微秒，历史无更新接口',
 PRIMARY KEY(tenant_id,reward_id,provider_id,provider_revision),
 FOREIGN KEY(tenant_id,reward_id) REFERENCES mk_referral_reward(tenant_id,reward_id),
 CHECK(provider_revision>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='可信履约业务修订永久历史摘要，支持非latest重放与冲突识别';

CREATE TABLE mk_referral_fulfillment_inbox (
 tenant_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '租户隔离首键',
 provider_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '认证供应方来源',
 event_id varchar(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '永久来源事件ID，同ID不能指向另一奖励',
 reward_id char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原事件关联奖励',
 provider_revision bigint unsigned NOT NULL COMMENT '原事件业务修订',
 business_digest varchar(71) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原事件规范业务快照摘要',
 outcome varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'APPLIED或REPLAYED_OLD_REVISION，仅表示本地持久受理',
 created_at datetime(6) NOT NULL COMMENT '首次事件提交UTC微秒',
 updated_at datetime(6) NOT NULL COMMENT '首次事件提交UTC微秒，永久回放不覆盖',
 PRIMARY KEY(tenant_id,provider_id,event_id),
 FOREIGN KEY(tenant_id,reward_id) REFERENCES mk_referral_reward(tenant_id,reward_id),
 CHECK(provider_revision>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='可信履约事件永久收据，与业务历史配额和Outbox同事务提交';
