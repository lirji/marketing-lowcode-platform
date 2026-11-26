ALTER TABLE mk_definition_version
  ADD COLUMN business_no VARCHAR(25) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '定义版本运营编号，编译和签名仍使用原定义 ID 与版本',
  ADD UNIQUE KEY uk_definition_version_business_no (business_no);
