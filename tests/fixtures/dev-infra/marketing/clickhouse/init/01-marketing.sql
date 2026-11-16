CREATE DATABASE IF NOT EXISTS marketing;

CREATE TABLE IF NOT EXISTS marketing.marketing_facts
(
    tenant_id LowCardinality(String),
    fact_id String,
    root_fact_id String,
    correction_of Nullable(String),
    fact_type LowCardinality(String),
    campaign_id String,
    experiment_id String,
    variant_id String,
    subject_hash FixedString(64),
    order_id String,
    amount_minor Int64,
    currency FixedString(3),
    actual_exposure UInt8,
    event_time DateTime64(3, 'UTC'),
    ingested_at DateTime64(3, 'UTC'),
    attributes_json String,
    version UInt64
)
ENGINE = ReplacingMergeTree(version)
PARTITION BY toYYYYMM(event_time)
ORDER BY (tenant_id, root_fact_id, fact_id)
TTL toDateTime(event_time) + INTERVAL 400 DAY DELETE;

CREATE TABLE IF NOT EXISTS marketing.campaign_daily
(
    tenant_id LowCardinality(String),
    campaign_id String,
    metric_date Date,
    variant_id String,
    exposures AggregateFunction(uniq, String),
    conversions AggregateFunction(uniq, String),
    revenue_minor AggregateFunction(sum, Int64),
    subsidy_minor AggregateFunction(sum, Int64)
)
ENGINE = AggregatingMergeTree
PARTITION BY toYYYYMM(metric_date)
ORDER BY (tenant_id, campaign_id, metric_date, variant_id);

CREATE TABLE IF NOT EXISTS marketing.decision_traces
(
    tenant_id LowCardinality(String),
    request_id String,
    subject_hash FixedString(64),
    order_id String,
    trace_json String,
    legal_hold UInt8 DEFAULT 0,
    created_at DateTime64(3, 'UTC'),
    expires_at DateTime64(3, 'UTC')
)
ENGINE = MergeTree
PARTITION BY toYYYYMM(created_at)
ORDER BY (tenant_id, request_id)
TTL if(legal_hold = 0, toDateTime(expires_at), toDateTime('2100-01-01 00:00:00')) DELETE;
