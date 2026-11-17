alter table mk_release_manifest
  add unique key uk_manifest_bundle_member (tenant_id, bundle_id, manifest_id);

create table mk_release_bundle_ack (
  tenant_id varchar(64) not null comment '可信身份中的租户编号',
  bundle_id varchar(64) not null comment '就绪证据绑定的不可变组合编号',
  manifest_id varchar(64) not null comment '组合中的精确成员清单编号',
  runtime_id varchar(128) not null comment '已签名的运行副本身份，每成员最多64个',
  ack_json text not null comment '完整组合ACK，包含内外签名，最大16KiB',
  acknowledged_at varchar(40) not null comment '运行端签发时间，固定九位小数的UTC时间',
  signature_value varchar(128) not null comment '外层Ed25519签名，用于同时间重复和冲突判断',
  primary key (tenant_id, bundle_id, manifest_id, runtime_id),
  constraint fk_bundle_ack_bundle foreign key (tenant_id, bundle_id)
    references mk_release_bundle(tenant_id, bundle_id),
  constraint fk_bundle_ack_member foreign key (tenant_id, bundle_id, manifest_id)
    references mk_release_manifest(tenant_id, bundle_id, manifest_id)
) comment '组合成员运行就绪的最新签名证据；读取时重验新鲜度，不代表已激活';
