ALTER TABLE mk_campaign
  ADD COLUMN business_no VARCHAR(25) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '活动的统一展示号，旧活动原标识仍用于关联',
  ADD UNIQUE KEY uk_campaign_business_no (business_no);

ALTER TABLE mk_approval_case
  ADD COLUMN business_no VARCHAR(25) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '审批单的统一展示号，旧审批原标识仍用于已签发制品',
  ADD UNIQUE KEY uk_approval_business_no (business_no);
