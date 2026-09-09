-- V5第三个检查约束是无订单触发原因；新增人工复评但仍禁止伪造订单版本。
ALTER TABLE mk_referral_evaluation_task DROP CHECK mk_referral_evaluation_task_chk_3;
ALTER TABLE mk_referral_evaluation_task ADD CONSTRAINT ck_evaluation_trigger CHECK
 ((trigger_resource_id IS NULL AND trigger_version=0 AND reason IN ('BOUND','MANUAL_REEVALUATION'))
 OR (trigger_resource_id IS NOT NULL AND trigger_version>0 AND reason NOT IN ('BOUND','MANUAL_REEVALUATION')));

CREATE TABLE mk_referral_reevaluation_command (
 tenant_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '认证租户隔离首键',
 actor_id varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '认证操作者，幂等键按操作者隔离',
 idempotency_key varchar(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '永久复评命令幂等键，不因重试重排队',
 operation_id char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '永久操作编号，用于审计关联',
 reward_id char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '固定奖励身份，不接受自报受益人SKU',
 payload_digest char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '奖励和规范操作原因摘要，同键异内容拒绝',
 reason varchar(128) NOT NULL COMMENT '有界操作原因，不写入公开事件载荷',
 state varchar(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'QUEUED仅代表排队成功，不代表复评完成',
 relation_count int unsigned NOT NULL COMMENT '同事务排队的关系数量，不是成功奖励份数',
 created_at datetime(6) NOT NULL COMMENT '原操作提交UTC微秒时间，重放不更新',
 updated_at datetime(6) NOT NULL COMMENT '原操作提交UTC微秒时间，永久收据不覆盖',
 PRIMARY KEY(tenant_id,actor_id,idempotency_key),
 UNIQUE KEY uk_reevaluation_operation(tenant_id,operation_id),
 FOREIGN KEY(tenant_id,reward_id) REFERENCES mk_referral_reward(tenant_id,reward_id),
 CHECK(state='QUEUED' AND relation_count>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='受控奖励复评永久命令收据，不允许绕过资格授权或再发同档奖励';
