create table mk_consent (
  tenant_id varchar(64) not null,
  subject_token varchar(256) not null,
  channel_name varchar(32) not null,
  allowed_value boolean not null,
  minor_value boolean not null,
  personalization_allowed boolean not null,
  version_no bigint not null,
  source_name varchar(128) not null,
  effective_at varchar(40) not null,
  updated_at varchar(40) not null,
  primary key (tenant_id, subject_token, channel_name)
);

create table mk_suppression (
  tenant_id varchar(64) not null,
  subject_token varchar(256) not null,
  channel_name varchar(32) not null,
  reason_text varchar(1000) not null,
  expires_at varchar(40),
  updated_at varchar(40) not null,
  primary key (tenant_id, subject_token, channel_name)
);

create table mk_frequency_policy (
  tenant_id varchar(64) not null,
  campaign_id varchar(128) not null,
  channel_name varchar(32) not null,
  window_seconds bigint not null,
  max_contacts integer not null,
  quiet_start varchar(16) not null,
  quiet_end varchar(16) not null,
  updated_at varchar(40) not null,
  primary key (tenant_id, campaign_id, channel_name)
);

create table mk_frequency_bucket (
  tenant_id varchar(64) not null,
  campaign_id varchar(128) not null,
  channel_name varchar(32) not null,
  subject_token varchar(256) not null,
  window_bucket bigint not null,
  contact_count integer not null,
  updated_at varchar(40) not null,
  primary key (tenant_id, campaign_id, channel_name, subject_token, window_bucket)
);

create table mk_template_version (
  tenant_id varchar(64) not null,
  template_id varchar(128) not null,
  version_no bigint not null,
  channel_name varchar(32) not null,
  content_text text not null,
  required_variables varchar(2000) not null,
  state_name varchar(32) not null,
  created_by varchar(128) not null,
  created_at varchar(40) not null,
  primary key (tenant_id, template_id, version_no)
);

create table mk_contact_attempt (
  tenant_id varchar(64) not null,
  contact_id varchar(64) not null,
  contact_key varchar(128) not null,
  subject_token varchar(256) not null,
  campaign_id varchar(128) not null,
  channel_name varchar(32) not null,
  template_id varchar(128) not null,
  template_version bigint not null,
  state_name varchar(32) not null,
  variables_json text not null,
  provider_request_id varchar(256),
  provider_code varchar(128),
  requested_at varchar(40) not null,
  updated_at varchar(40) not null,
  primary key (tenant_id, contact_id),
  constraint uq_contact_key unique (tenant_id, contact_key)
);
create index ix_contact_subject on mk_contact_attempt(tenant_id, subject_token, channel_name, requested_at);

create table mk_provider_receipt (
  tenant_id varchar(64) not null,
  provider_event_id varchar(256) not null,
  provider_request_id varchar(256) not null,
  status_name varchar(32) not null,
  occurred_at varchar(40) not null,
  attributes_json text not null,
  primary key (tenant_id, provider_event_id)
);

create table mk_contact_dlq (
  tenant_id varchar(64) not null,
  dlq_id varchar(64) not null,
  contact_id varchar(64) not null,
  reason_code varchar(128) not null,
  state_name varchar(32) not null,
  created_at varchar(40) not null,
  primary key (tenant_id, dlq_id)
);

create table mk_engagement_outbox (
  tenant_id varchar(64) not null,
  event_id varchar(64) not null,
  contact_id varchar(64) not null,
  event_type varchar(128) not null,
  destination_topic varchar(249) not null,
  partition_key varchar(256) not null,
  stream_sequence bigint not null,
  payload_json mediumtext not null,
  publish_attempts integer not null default 0,
  next_attempt_at varchar(40) not null,
  last_error varchar(1000) not null default '',
  created_at varchar(40) not null,
  published_at varchar(40),
  dead_lettered_at varchar(40),
  primary key (tenant_id, event_id),
  constraint uq_engagement_outbox_sequence unique (tenant_id, contact_id, stream_sequence)
);
create index ix_engagement_outbox_pending on mk_engagement_outbox(published_at, dead_lettered_at, next_attempt_at, created_at);

create table mk_engagement_outbox_position (
  tenant_id varchar(64) not null,
  contact_id varchar(64) not null,
  last_sequence bigint not null,
  updated_at varchar(40) not null,
  primary key (tenant_id, contact_id)
);

create table mk_engagement_command (
  tenant_id varchar(64) not null,
  command_id varchar(64) not null,
  effect_type varchar(32) not null,
  enrollment_id varchar(64) not null,
  payload_hash varchar(64) not null,
  payload_json mediumtext not null,
  state_name varchar(32) not null,
  contact_id varchar(64),
  provider_code varchar(128) not null default '',
  last_error varchar(1000) not null default '',
  created_at varchar(40) not null,
  updated_at varchar(40) not null,
  primary key (tenant_id, command_id)
);
create index ix_engagement_command_state on mk_engagement_command(state_name, updated_at);
