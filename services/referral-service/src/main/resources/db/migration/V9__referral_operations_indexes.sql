-- 运营按活动及资源ID seek分页，避免排序整活动历史或使用大OFFSET。
CREATE INDEX ix_participant_campaign_seek ON mk_referral_participant(tenant_id,campaign_id,participant_id);
CREATE INDEX ix_relation_campaign_seek ON mk_referral_relation(tenant_id,campaign_id,relation_id);
CREATE INDEX ix_reward_campaign_seek ON mk_referral_reward(tenant_id,campaign_id,reward_id);
