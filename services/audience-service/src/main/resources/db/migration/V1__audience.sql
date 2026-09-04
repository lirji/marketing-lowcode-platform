create table mk_field_definition (
  tenant_id varchar(64) not null,
  field_id varchar(128) not null,
  value_type varchar(32) not null,
  owner_name varchar(128) not null,
  provenance varchar(1000) not null,
  classification varchar(32) not null,
  allowed_uses varchar(1000) not null,
  max_age_seconds bigint not null,
  null_policy varchar(32) not null,
  missing_policy varchar(32) not null,
  retention_days integer not null,
  created_at varchar(40) not null,
  primary key (tenant_id, field_id)
);

create table mk_segment_definition (
  tenant_id varchar(64) not null,
  segment_id varchar(128) not null,
  version_no bigint not null,
  name varchar(256) not null,
  rule_json text not null,
  rule_hash varchar(80) not null,
  state_name varchar(32) not null,
  created_by varchar(128) not null,
  created_at varchar(40) not null,
  primary key (tenant_id, segment_id, version_no),
  constraint uq_segment_hash unique (tenant_id, segment_id, rule_hash)
);

create table mk_audience_snapshot (
  tenant_id varchar(64) not null,
  snapshot_id varchar(64) not null,
  segment_id varchar(128) not null,
  segment_version bigint not null,
  as_of_time varchar(40) not null,
  watermark_time varchar(40) not null,
  expires_at varchar(40) not null,
  member_count bigint not null,
  checksum varchar(80) not null,
  state_name varchar(32) not null,
  created_at varchar(40) not null,
  primary key (tenant_id, snapshot_id),
  constraint fk_snapshot_segment foreign key (tenant_id, segment_id, segment_version)
    references mk_segment_definition(tenant_id, segment_id, version_no)
);
create index ix_snapshot_segment on mk_audience_snapshot(tenant_id, segment_id, segment_version, state_name);

create table mk_audience_member (
  tenant_id varchar(64) not null,
  snapshot_id varchar(64) not null,
  subject_hash varchar(64) not null,
  membership_version bigint not null,
  active_value boolean not null,
  updated_at varchar(40) not null,
  primary key (tenant_id, snapshot_id, subject_hash),
  constraint fk_member_snapshot foreign key (tenant_id, snapshot_id)
    references mk_audience_snapshot(tenant_id, snapshot_id)
);
