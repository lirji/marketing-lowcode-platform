ALTER TABLE mk_benefit_definition
    ADD COLUMN benefit_sku_id varchar(128) NULL COMMENT '绑定的权益中台SKU模板标识；草稿可为空' AFTER resource_key;

CREATE INDEX ix_benefit_definition_sku
    ON mk_benefit_definition (tenant_id, benefit_sku_id, version_no);
