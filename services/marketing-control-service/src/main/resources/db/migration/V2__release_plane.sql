create table mk_release_slot (
  tenant_id varchar(64) not null,
  environment_name varchar(32) not null,
  cell_id varchar(32) not null,
  runtime_name varchar(64) not null,
  namespace_name varchar(128) not null,
  stable_generation bigint not null,
  desired_generation bigint not null,
  latest_generation bigint not null,
  activation_sequence bigint not null,
  updated_at varchar(40) not null,
  primary key (tenant_id, environment_name, cell_id, runtime_name, namespace_name)
);

create table mk_release_manifest (
  tenant_id varchar(64) not null,
  manifest_id varchar(64) not null,
  environment_name varchar(32) not null,
  cell_id varchar(32) not null,
  runtime_name varchar(64) not null,
  namespace_name varchar(128) not null,
  generation_no bigint not null,
  state_name varchar(32) not null,
  manifest_json text not null,
  created_at varchar(40) not null,
  primary key (tenant_id, manifest_id),
  constraint uq_release_generation unique
    (tenant_id, environment_name, cell_id, runtime_name, namespace_name, generation_no)
);
create index ix_release_slot_state on mk_release_manifest
  (tenant_id, environment_name, cell_id, runtime_name, namespace_name, state_name);

create table mk_runtime_ack (
  tenant_id varchar(64) not null,
  manifest_id varchar(64) not null,
  runtime_id varchar(128) not null,
  status_name varchar(32) not null,
  build_digest varchar(128) not null,
  supported_abis varchar(1000) not null,
  warmed_artifact_ids varchar(4000) not null,
  capacity_value bigint not null,
  acknowledged_at varchar(40) not null,
  signature_key_id varchar(128) not null,
  signature_value varchar(1000) not null,
  primary key (tenant_id, manifest_id, runtime_id),
  constraint fk_ack_manifest foreign key (tenant_id, manifest_id)
    references mk_release_manifest(tenant_id, manifest_id)
);

create table mk_kill_switch (
  tenant_id varchar(64) not null,
  namespace_name varchar(128) not null,
  switch_sequence bigint not null,
  enabled_value boolean not null,
  reason_text varchar(1000) not null,
  updated_by varchar(128) not null,
  updated_at varchar(40) not null,
  directive_json mediumtext not null,
  primary key (tenant_id, namespace_name)
);

create table mk_activation_directive (
  tenant_id varchar(64) not null,
  directive_id varchar(64) not null,
  environment_name varchar(32) not null,
  cell_id varchar(32) not null,
  runtime_name varchar(64) not null,
  namespace_name varchar(128) not null,
  activation_sequence bigint not null,
  generation_no bigint not null,
  directive_json mediumtext not null,
  created_at varchar(40) not null,
  primary key (tenant_id, directive_id),
  constraint uq_activation_sequence unique
    (tenant_id, environment_name, cell_id, runtime_name, namespace_name, activation_sequence)
);

create table mk_control_command (
  tenant_id varchar(64) not null,
  operation_name varchar(96) not null,
  idempotency_key varchar(128) not null,
  payload_hash varchar(64) not null,
  state_name varchar(32) not null,
  response_json text,
  created_at varchar(40) not null,
  expires_at varchar(40) not null,
  primary key (tenant_id, operation_name, idempotency_key)
);
create index ix_control_command_expiry on mk_control_command(expires_at);
