ALTER TABLE mk_contact_attempt
    ADD COLUMN business_no VARCHAR(25) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '独立于渠道请求键的触达运营编号',
    ADD COLUMN created_at VARCHAR(40) NULL COMMENT '首次持久受理UTC时间，旧行优先使用原持久事件核验',
    ADD UNIQUE KEY uk_contact_business_no (business_no);
