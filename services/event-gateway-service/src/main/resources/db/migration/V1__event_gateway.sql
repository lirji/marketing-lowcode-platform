create table mk_source_registration (
  tenant_id varchar(64) not null,
  source_id varchar(128) not null,
  source_uri varchar(1000) not null,
  source_uri_hash varchar(64) not null,
  allowed_types varchar(2000) not null,
  schema_versions varchar(1000) not null,
  max_lateness_seconds bigint not null,
  enabled_value boolean not null,
  created_at varchar(40) not null,
  primary key (tenant_id, source_id),
  constraint uq_source_uri_hash unique (tenant_id, source_uri_hash)
);

create table mk_event_receipt (
  tenant_id varchar(64) not null,
  receipt_id varchar(64) not null,
  source_id varchar(128) not null,
  event_id varchar(128) not null,
  event_type varchar(128) not null,
  business_key varchar(256) not null,
  aggregate_version bigint,
  status_name varchar(32) not null,
  reason_code varchar(64) not null,
  payload_hash varchar(64) not null,
  event_json mediumtext not null,
  occurred_at varchar(40) not null,
  ingested_at varchar(40) not null,
  primary key (tenant_id, receipt_id),
  constraint uq_event_dedup unique (tenant_id, source_id, event_id)
);
create index ix_receipt_status_time on mk_event_receipt(tenant_id, status_name, ingested_at);

create table mk_event_sequence (
  tenant_id varchar(64) not null,
  source_id varchar(128) not null,
  business_key varchar(256) not null,
  last_version bigint not null,
  updated_at varchar(40) not null,
  primary key (tenant_id, source_id, business_key)
);

create table mk_quarantine (
  tenant_id varchar(64) not null,
  quarantine_id varchar(64) not null,
  receipt_id varchar(64) not null,
  reason_code varchar(64) not null,
  event_json mediumtext not null,
  state_name varchar(32) not null,
  created_at varchar(40) not null,
  replayed_at varchar(40),
  primary key (tenant_id, quarantine_id),
  constraint uq_quarantine_receipt unique (tenant_id, receipt_id),
  constraint fk_quarantine_receipt foreign key (tenant_id, receipt_id)
    references mk_event_receipt(tenant_id, receipt_id)
);

create table mk_event_outbox (
  tenant_id varchar(64) not null,
  outbox_id varchar(64) not null,
  receipt_id varchar(64) not null,
  event_type varchar(128) not null,
  destination_topic varchar(249) not null,
  partition_key varchar(256) not null,
  stream_sequence bigint not null,
  payload_json mediumtext not null,
  created_at varchar(40) not null,
  published_at varchar(40),
  dead_lettered_at varchar(40),
  publish_attempts integer not null default 0,
  next_attempt_at varchar(40) not null,
  last_error varchar(1000) not null default '',
  primary key (tenant_id, outbox_id),
  constraint uq_event_outbox_receipt unique (tenant_id, receipt_id)
);
create index ix_event_outbox_pending on mk_event_outbox(published_at, next_attempt_at, created_at);
create unique index uq_event_outbox_stream on mk_event_outbox
  (tenant_id, destination_topic, partition_key, stream_sequence);

create table mk_event_stream_position (
  tenant_id varchar(64) not null,
  destination_topic varchar(249) not null,
  partition_key varchar(256) not null,
  last_sequence bigint not null,
  updated_at varchar(40) not null,
  primary key (tenant_id, destination_topic, partition_key)
);
