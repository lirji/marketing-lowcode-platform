ALTER TABLE mk_release_bundle
  ADD COLUMN business_no VARCHAR(25) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '发布组合运营编号，签名继续引用原组合 ID',
  ADD UNIQUE KEY uk_release_bundle_business_no (business_no);

ALTER TABLE mk_bundle_release_operation
  ADD COLUMN business_no VARCHAR(25) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '发布操作运营编号，运行授权沿用原操作 ID',
  ADD UNIQUE KEY uk_bundle_operation_business_no (business_no);

ALTER TABLE mk_bundle_activation_schedule
  ADD COLUMN business_no VARCHAR(25) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '发布排期运营编号，租约恢复继续引用原排期 ID',
  ADD UNIQUE KEY uk_bundle_schedule_business_no (business_no);
