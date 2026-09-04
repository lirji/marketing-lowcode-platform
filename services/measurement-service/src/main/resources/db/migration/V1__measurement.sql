create table mk_experiment (
  tenant_id varchar(64) not null,
  experiment_id varchar(128) not null,
  version_no varchar(64) not null,
  layer_name varchar(128) not null,
  definition_json text not null,
  state_name varchar(32) not null,
  created_at varchar(40) not null,
  primary key (tenant_id, experiment_id, version_no)
);

create table mk_experiment_assignment (
  tenant_id varchar(64) not null,
  experiment_id varchar(128) not null,
  version_no varchar(64) not null,
  layer_name varchar(128) not null,
  randomization_unit varchar(256) not null,
  variant_id varchar(128) not null,
  holdout_value boolean not null,
  bucket_no integer not null,
  assigned_at varchar(40) not null,
  primary key (tenant_id, experiment_id, version_no, randomization_unit)
);

create table mk_experiment_layer_assignment (
  tenant_id varchar(64) not null,
  layer_name varchar(128) not null,
  randomization_unit varchar(256) not null,
  experiment_id varchar(128) not null,
  assigned_at varchar(40) not null,
  primary key (tenant_id, layer_name, randomization_unit)
);

create table mk_fact (
  tenant_id varchar(64) not null,
  event_id varchar(128) not null,
  fact_type varchar(64) not null,
  business_key varchar(256) not null,
  subject_hash varchar(64) not null,
  occurred_at varchar(40) not null,
  ingested_at varchar(40) not null,
  schema_version varchar(32) not null,
  payload_hash varchar(64) not null,
  attributes_json text not null,
  correction_of varchar(128) not null,
  correction_root_id varchar(128) not null,
  corrected_value boolean not null,
  revenue_minor bigint not null,
  cost_minor bigint not null,
  experiment_id varchar(128) not null,
  experiment_version varchar(64) not null,
  variant_id varchar(128) not null,
  primary key (tenant_id, event_id)
);
create index ix_fact_projection on mk_fact(tenant_id, fact_type, occurred_at, corrected_value);
create index ix_fact_subject on mk_fact(tenant_id, subject_hash, occurred_at);
create index ix_fact_experiment on mk_fact(tenant_id, experiment_id, experiment_version, fact_type, corrected_value);
create index ix_fact_correction_root on mk_fact(tenant_id, correction_root_id, corrected_value);

create table mk_dashboard_projection_delta (
  tenant_id varchar(64) not null,
  delta_id varchar(64) not null,
  root_event_id varchar(128) not null,
  source_event_id varchar(128) not null,
  revision_no bigint not null,
  operation_name varchar(16) not null,
  fact_type varchar(64) not null,
  business_key varchar(256) not null,
  campaign_id varchar(128) not null,
  experiment_id varchar(128) not null,
  variant_id varchar(128) not null,
  subject_hash varchar(64) not null,
  count_delta bigint not null,
  revenue_delta_minor bigint not null,
  cost_delta_minor bigint not null,
  occurred_at varchar(40) not null,
  ingested_at varchar(40) not null,
  payload_hash varchar(64) not null,
  source_topic varchar(249) not null,
  source_partition integer,
  source_offset bigint,
  projected_at varchar(40) not null,
  primary key (tenant_id, delta_id),
  constraint uq_dashboard_projection_source unique (source_topic, source_partition, source_offset)
);
create index ix_dashboard_projection_range on mk_dashboard_projection_delta(tenant_id, occurred_at, fact_type);

create table mk_projection_watermark_config (
  tenant_id varchar(64) not null,
  projection_name varchar(128) not null,
  partition_count integer not null,
  updated_at varchar(40) not null,
  primary key (tenant_id, projection_name)
);

create table mk_projection_partition_watermark (
  tenant_id varchar(64) not null,
  projection_name varchar(128) not null,
  partition_id integer not null,
  source_offset bigint not null,
  complete_through_epoch_ms bigint not null,
  updated_at varchar(40) not null,
  primary key (tenant_id, projection_name, partition_id),
  constraint fk_projection_watermark_config foreign key (tenant_id, projection_name)
    references mk_projection_watermark_config(tenant_id, projection_name)
);

create table mk_decision_trace (
  tenant_id varchar(64) not null,
  trace_id varchar(128) not null,
  request_id varchar(128) not null,
  order_id varchar(128) not null,
  subject_hash varchar(64) not null,
  generation_no bigint not null,
  duration_micros bigint not null,
  candidates_json text not null,
  pricing_json text not null,
  terms_version varchar(128) not null,
  expires_at varchar(40) not null,
  legal_hold boolean not null,
  created_at varchar(40) not null,
  primary key (tenant_id, trace_id),
  constraint uq_trace_request unique (tenant_id, request_id)
);
create index ix_trace_order on mk_decision_trace(tenant_id, order_id, created_at);
