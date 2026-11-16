-- LocalTime.toString() 最长为 HH:mm:ss.SSSSSSSSS（18字符）；保留API已接受的纳秒精度。
-- 只扩容已有列，旧版本读写仍兼容，不修改既有迁移或截断用户配置。
ALTER TABLE mk_frequency_policy
  MODIFY COLUMN quiet_start varchar(18) NOT NULL COMMENT '免打扰开始时间（ISO本地时间，支持纳秒）',
  MODIFY COLUMN quiet_end varchar(18) NOT NULL COMMENT '免打扰结束时间（ISO本地时间，支持纳秒）';
