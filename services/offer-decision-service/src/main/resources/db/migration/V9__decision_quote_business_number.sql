ALTER TABLE mk_decision_command
    ADD COLUMN business_no VARCHAR(25) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '决策报价运营编号，与轨迹一同过期',
    ADD COLUMN quote_ref VARCHAR(64) GENERATED ALWAYS AS (
        CASE WHEN JSON_VALID(trace_json) THEN JSON_UNQUOTE(JSON_EXTRACT(trace_json, '$.traceId')) END
    ) STORED COMMENT '同一报价的原签名引用，可按旧号查历史轨迹',
    ADD UNIQUE KEY uk_decision_quote_business_no (business_no),
    ADD KEY ix_decision_original_quote (tenant_id,quote_ref);
