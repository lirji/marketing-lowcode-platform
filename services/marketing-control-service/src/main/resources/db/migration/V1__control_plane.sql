create table mk_campaign (
  tenant_id varchar(64) not null,
  campaign_id varchar(64) not null,
  name varchar(200) not null,
  objective varchar(1000) not null,
  status varchar(32) not null,
  organization_id varchar(64),
  shop_id varchar(64),
  created_at varchar(40) not null,
  updated_at varchar(40) not null,
  primary key (tenant_id, campaign_id)
);
create index ix_campaign_tenant_status_time on mk_campaign(tenant_id, status, created_at);

create table mk_definition_version (
  tenant_id varchar(64) not null,
  definition_id varchar(128) not null,
  campaign_id varchar(64) not null,
  version_no bigint not null,
  dialect varchar(64) not null,
  graph_json text not null,
  semantic_hash varchar(80) not null,
  status varchar(32) not null,
  created_by varchar(128) not null,
  created_at varchar(40) not null,
  updated_at varchar(40) not null,
  primary key (tenant_id, definition_id, version_no),
  constraint fk_definition_campaign foreign key (tenant_id, campaign_id)
    references mk_campaign(tenant_id, campaign_id),
  constraint uq_definition_hash unique (tenant_id, definition_id, semantic_hash)
);
create index ix_definition_campaign on mk_definition_version(tenant_id, campaign_id, status);

create table mk_approval_case (
  tenant_id varchar(64) not null,
  case_id varchar(64) not null,
  definition_id varchar(128) not null,
  definition_version bigint not null,
  submitted_by varchar(128) not null,
  required_roles varchar(256) not null,
  status varchar(32) not null,
  created_at varchar(40) not null,
  updated_at varchar(40) not null,
  primary key (tenant_id, case_id),
  constraint fk_approval_definition foreign key (tenant_id, definition_id, definition_version)
    references mk_definition_version(tenant_id, definition_id, version_no)
);

create table mk_approval_decision (
  tenant_id varchar(64) not null,
  case_id varchar(64) not null,
  role_name varchar(32) not null,
  actor_id varchar(128) not null,
  decided_at varchar(40) not null,
  primary key (tenant_id, case_id, role_name),
  constraint uq_approval_actor unique (tenant_id, case_id, actor_id),
  constraint fk_approval_case foreign key (tenant_id, case_id) references mk_approval_case(tenant_id, case_id)
);

create table mk_terms_snapshot (
  tenant_id varchar(64) not null,
  terms_id varchar(64) not null,
  definition_id varchar(128) not null,
  definition_version bigint not null,
  content_json text not null,
  content_hash varchar(80) not null,
  created_at varchar(40) not null,
  primary key (tenant_id, terms_id),
  constraint uq_terms_definition unique (tenant_id, definition_id, definition_version),
  constraint fk_terms_definition foreign key (tenant_id, definition_id, definition_version)
    references mk_definition_version(tenant_id, definition_id, version_no)
);

create table mk_audit (
  sequence_no bigint not null auto_increment,
  tenant_id varchar(64) not null,
  audit_id varchar(64) not null,
  chain_index bigint not null,
  actor_id varchar(128) not null,
  action_name varchar(64) not null,
  resource_ref varchar(256) not null,
  occurred_at varchar(40) not null,
  previous_hash varchar(64) not null,
  entry_hash varchar(64) not null,
  primary key (tenant_id, audit_id),
  constraint uq_audit_sequence unique (sequence_no),
  constraint uq_audit_chain_index unique (tenant_id, chain_index),
  constraint uq_audit_hash unique (tenant_id, entry_hash)
);
create index ix_audit_chain on mk_audit(tenant_id, sequence_no);

create table mk_audit_head (
  tenant_id varchar(64) not null,
  last_chain_index bigint not null,
  last_entry_hash varchar(64) not null,
  updated_at varchar(40) not null,
  primary key (tenant_id)
);

create table mk_outbox (
  tenant_id varchar(64) not null,
  event_id varchar(64) not null,
  aggregate_type varchar(64) not null,
  aggregate_id varchar(128) not null,
  event_type varchar(128) not null,
  payload_json mediumtext not null,
  occurred_at varchar(40) not null,
  published_at varchar(40),
  destination_topic varchar(249) not null,
  partition_key varchar(256) not null,
  stream_sequence bigint not null,
  publish_attempts integer not null default 0,
  next_attempt_at varchar(40) not null,
  last_error varchar(1000) not null default '',
  primary key (tenant_id, event_id)
);
create index ix_control_outbox_pending on mk_outbox(published_at, next_attempt_at, occurred_at);
create unique index uq_control_outbox_stream on mk_outbox(tenant_id, partition_key, stream_sequence);
