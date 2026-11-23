ALTER TABLE mk_promotion_application
  ADD COLUMN campaign_id varchar(64) NULL COMMENT '可信签名报价绑定的活动ID，NULL表示活动待归属';

-- 兼容旧流水的零位或可变位小数秒；保留实际入账时间原值和纳秒精度。
ALTER TABLE mk_funding_ledger
  ADD COLUMN posting_time_utc varchar(30) CHARACTER SET ascii COLLATE ascii_bin
    GENERATED ALWAYS AS (CONCAT(LEFT(occurred_at, 19), '.',
      RPAD(CASE WHEN SUBSTRING(occurred_at, 20, 1) = '.'
        THEN SUBSTRING(occurred_at, 21, CHAR_LENGTH(occurred_at) - 21) ELSE '' END, 9, '0'), 'Z'))
    VIRTUAL COMMENT '规范化UTC入账时间，按时间与流水ID稳定分页',
  ADD INDEX ix_ledger_posting_time (tenant_id, posting_time_utc, ledger_id);
