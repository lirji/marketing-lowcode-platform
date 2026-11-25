ALTER TABLE mk_promotion_application
    ADD COLUMN checkout_ref CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '交易试点原始报价引用',
    ADD COLUMN checkout_trade_tenant VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '原生交易租户',
    ADD COLUMN checkout_funding_receipt VARCHAR(4096) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '首次提交后可重放的预算签名回执',
    ADD UNIQUE KEY uk_funding_checkout_ref (tenant_id, checkout_ref),
    ADD UNIQUE KEY uk_funding_native_order (checkout_trade_tenant, order_id),
    ADD CONSTRAINT ck_funding_checkout_binding CHECK (
        (checkout_ref IS NULL AND checkout_trade_tenant IS NULL AND checkout_funding_receipt IS NULL)
        OR (checkout_ref IS NOT NULL AND checkout_trade_tenant IS NOT NULL AND checkout_funding_receipt IS NOT NULL)
    );
