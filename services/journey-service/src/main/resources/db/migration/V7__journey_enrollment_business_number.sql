ALTER TABLE mk_enrollment
  ADD COLUMN business_no VARCHAR(25) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '旅程参与展示号，流式状态和计时器继续使用原参与 ID',
  ADD UNIQUE KEY uk_journey_enrollment_business_no (business_no);
