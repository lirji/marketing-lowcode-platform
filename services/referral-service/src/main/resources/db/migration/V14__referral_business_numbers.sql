ALTER TABLE mk_referral_participant
  ADD COLUMN business_no VARCHAR(25) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '裂变参与运营编号，签发令牌沿用原业务键',
  ADD UNIQUE KEY uk_referral_participant_business_no (business_no);

ALTER TABLE mk_referral_relation
  ADD COLUMN business_no VARCHAR(25) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '裂变归因关系运营编号，评估沿用原业务键',
  ADD UNIQUE KEY uk_referral_relation_business_no (business_no);

ALTER TABLE mk_referral_reward
  ADD COLUMN business_no VARCHAR(25) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '裂变奖励运营编号，发放签名和账务沿用奖励摘要键',
  ADD UNIQUE KEY uk_referral_reward_business_no (business_no);
