ALTER TABLE mk_audience_snapshot
  ADD COLUMN business_no VARCHAR(25) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '人群快照展示号，成员仍使用原快照 ID',
  ADD UNIQUE KEY uk_audience_snapshot_business_no (business_no);
