ALTER TABLE mk_event_receipt
    ADD COLUMN business_no VARCHAR(25) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '可查询的事件接收回执编号',
    ADD UNIQUE KEY uk_event_receipt_business_no (business_no);

ALTER TABLE mk_quarantine
    ADD COLUMN business_no VARCHAR(25) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '运营用于核对和重放的隔离记录编号',
    ADD UNIQUE KEY uk_quarantine_business_no (business_no);
