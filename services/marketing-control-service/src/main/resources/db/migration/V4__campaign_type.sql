-- 历史活动及旧请求缺省STANDARD，不依据名称或定义猜测历史类型。
ALTER TABLE mk_campaign ADD COLUMN campaign_type varchar(32) NOT NULL DEFAULT 'STANDARD' COMMENT '创建时固定业务类型STANDARD或REFERRAL，不代表发布就绪';
ALTER TABLE mk_campaign ADD CONSTRAINT ck_campaign_type CHECK (campaign_type IN ('STANDARD','REFERRAL'));
CREATE INDEX ix_campaign_tenant_type_time ON mk_campaign(tenant_id,campaign_type,created_at);
