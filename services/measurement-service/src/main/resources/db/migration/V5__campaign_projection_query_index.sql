-- 活动筛选先限定租户与活动，再扫描有界时间范围；保留原索引用于全部活动统计。
CREATE INDEX ix_dashboard_projection_campaign_range
    ON mk_dashboard_projection_delta (tenant_id, campaign_id, occurred_at)
    COMMENT '按租户和活动筛选统计窗口';
