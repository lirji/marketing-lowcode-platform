ALTER TABLE mk_promotion_application
  ADD COLUMN campaign_attributed_by text NULL COMMENT '通过原始报价补录活动归属的认证操作者',
  ADD COLUMN campaign_attributed_at varchar(40) NULL COMMENT '原始报价活动归属补录的UTC时间，预留时直接归属则为空',
  ADD CONSTRAINT ck_application_campaign_audit CHECK (
    (campaign_attributed_by IS NULL AND campaign_attributed_at IS NULL)
    OR (campaign_id IS NOT NULL AND campaign_attributed_by IS NOT NULL AND campaign_attributed_at IS NOT NULL)
  );
