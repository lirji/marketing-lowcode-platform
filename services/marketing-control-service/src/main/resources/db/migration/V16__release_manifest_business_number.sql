ALTER TABLE mk_release_manifest
    ADD COLUMN business_no VARCHAR(25) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '运营清单编号，签名继续绑定原清单标识',
    ADD UNIQUE KEY uk_release_manifest_business_no (business_no);
