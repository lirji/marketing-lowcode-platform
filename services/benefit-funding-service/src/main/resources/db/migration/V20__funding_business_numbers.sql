ALTER TABLE mk_promotion_application
  ADD COLUMN business_no VARCHAR(25) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '资金申请展示号，签名及交易绑定继续使用原始申请标识',
  ADD UNIQUE KEY uk_promotion_business_no (business_no);

ALTER TABLE mk_funding_ledger
  ADD COLUMN business_no VARCHAR(25) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '补贴及资金流水展示号，旧流水关联保持原标识',
  ADD UNIQUE KEY uk_funding_ledger_business_no (business_no);
