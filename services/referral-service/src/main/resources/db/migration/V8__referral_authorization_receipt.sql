-- 原确认事实永久只追加；当前取消状态由reward返回，不修改首次确认时间/摘要。
CREATE TABLE mk_referral_authorization_receipt (
 tenant_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '租户隔离及所有唯一键首列',
 reward_id char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '永久奖励身份，只能首次确认一次',
 source_request_id varchar(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原外部幂等请求号，不随重签重试变化',
 stable_claims_digest varchar(71) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '完整固定授权声明SHA256，来源适配器已验签，不含重签时间',
 qualification_revision bigint unsigned NOT NULL COMMENT '首次确认使用的资格修订，历史回放不覆盖',
 confirmation_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '首次确认随机回执ID，超时恢复返回同一值',
 authorization_sequence bigint unsigned NOT NULL COMMENT '首次确认序号，当前同奖励固定为一',
 confirmed_seconds bigint NOT NULL COMMENT '首次确认UTC原始秒，永久不变',
 confirmed_nanos int unsigned NOT NULL COMMENT '首次确认原始纳秒，SQL微秒不能改变授权事实',
 created_at datetime(6) NOT NULL COMMENT '记录创建UTC微秒，仅作扫描和审计时间',
 updated_at datetime(6) NOT NULL COMMENT '记录创建UTC微秒，首次回执无更新接口',
 PRIMARY KEY(tenant_id,reward_id),
 UNIQUE KEY uq_authorization_source(tenant_id,source_request_id),
 UNIQUE KEY uq_authorization_confirmation(tenant_id,confirmation_id),
 FOREIGN KEY(tenant_id,reward_id) REFERENCES mk_referral_reward(tenant_id,reward_id),
 CHECK(qualification_revision>0 AND authorization_sequence=1 AND confirmed_nanos<1000000000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='裂变永久首次授权回执，未签外部响应且不表示当前投递许可或实际发券';
