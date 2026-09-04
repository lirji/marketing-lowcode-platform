create table mk_runtime_slot (
  tenant_id varchar(64) not null,
  environment_name varchar(32) not null,
  cell_id varchar(32) not null,
  runtime_name varchar(64) not null,
  namespace_name varchar(128) not null,
  desired_generation bigint not null,
  activation_sequence bigint not null,
  directive_signature varchar(256) not null,
  directive_json mediumtext not null,
  updated_at varchar(40) not null,
  primary key (tenant_id, environment_name, cell_id, runtime_name, namespace_name)
);

create table mk_runtime_generation (
  tenant_id varchar(64) not null,
  environment_name varchar(32) not null,
  cell_id varchar(32) not null,
  runtime_name varchar(64) not null,
  namespace_name varchar(128) not null,
  generation_no bigint not null,
  release_key_id varchar(128) not null,
  manifest_json mediumtext not null,
  artifact_payload mediumblob not null,
  manifest_signature varchar(256) not null,
  warmed_at varchar(40) not null,
  primary key (tenant_id, environment_name, cell_id, runtime_name, namespace_name, generation_no)
);

create table mk_decision_command (
  tenant_id varchar(64) not null,
  idempotency_key varchar(128) not null,
  payload_hash varchar(64) not null,
  state_name varchar(32) not null,
  response_json mediumtext,
  created_at varchar(40) not null,
  expires_at varchar(40) not null,
  primary key (tenant_id, idempotency_key)
);
create index ix_decision_command_expiry on mk_decision_command(expires_at);

create table mk_audience_membership_projection (
  tenant_id varchar(64) not null,
  audience_id varchar(128) not null,
  subject_hash varchar(64) not null,
  member_value boolean not null,
  membership_version bigint not null,
  expires_at varchar(40) not null,
  updated_at varchar(40) not null,
  primary key (tenant_id, audience_id, subject_hash)
);

create table mk_decision_kill_switch (
  tenant_id varchar(64) not null,
  namespace_name varchar(128) not null,
  switch_sequence bigint not null,
  enabled_value boolean not null,
  reason_text varchar(1000) not null,
  directive_signature varchar(1000) not null,
  directive_json mediumtext not null,
  updated_at varchar(40) not null,
  primary key (tenant_id, namespace_name)
);
