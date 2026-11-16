-- 历史记录没有保留收件人/时区等完整请求，不能伪造指纹；仅新请求写入。
ALTER TABLE mk_contact_attempt
  ADD COLUMN request_fingerprint char(64) NULL COMMENT '原始触达请求的SHA-256指纹；历史缺失时禁止自动重发';
