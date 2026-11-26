ALTER TABLE mk_segment_definition
    ADD COLUMN business_no VARCHAR(25) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '人群稳定编号，仅存于首版本',
    ADD UNIQUE KEY uk_segment_business_no (business_no);
