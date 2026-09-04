create table mk_journey_definition (
  tenant_id varchar(64) not null,
  journey_id varchar(128) not null,
  version_no bigint not null,
  plan_json mediumtext not null,
  state_name varchar(32) not null,
  created_by varchar(128) not null,
  created_at varchar(40) not null,
  primary key (tenant_id, journey_id, version_no)
);

create table mk_enrollment (
  tenant_id varchar(64) not null,
  enrollment_id varchar(64) not null,
  journey_id varchar(128) not null,
  journey_version bigint not null,
  subject_token varchar(256) not null,
  trigger_event_id varchar(128) not null,
  status_name varchar(32) not null,
  current_node_id varchar(128) not null,
  snapshot_json mediumtext not null,
  projection_topic varchar(249) not null default '',
  projection_partition integer not null default -1,
  projection_offset bigint not null default -1,
  created_at varchar(40) not null,
  updated_at varchar(40) not null,
  primary key (tenant_id, enrollment_id),
  constraint uq_enrollment_trigger unique
    (tenant_id, journey_id, journey_version, subject_token, trigger_event_id),
  constraint fk_enrollment_journey foreign key (tenant_id, journey_id, journey_version)
    references mk_journey_definition(tenant_id, journey_id, version_no)
);
create index ix_enrollment_runtime on mk_enrollment(tenant_id, journey_id, status_name, updated_at);

create table mk_node_effect_intent (
  tenant_id varchar(64) not null,
  command_id varchar(64) not null,
  enrollment_id varchar(64) not null,
  node_id varchar(128) not null,
  effect_type varchar(32) not null,
  payload_json mediumtext not null,
  state_name varchar(32) not null,
  created_at varchar(40) not null,
  primary key (tenant_id, command_id),
  constraint fk_effect_enrollment foreign key (tenant_id, enrollment_id)
    references mk_enrollment(tenant_id, enrollment_id)
);

create table mk_journey_timer (
  tenant_id varchar(64) not null,
  timer_key varchar(256) not null,
  enrollment_id varchar(64) not null,
  fire_at varchar(40) not null,
  state_name varchar(32) not null,
  created_at varchar(40) not null,
  primary key (tenant_id, timer_key),
  constraint fk_timer_enrollment foreign key (tenant_id, enrollment_id)
    references mk_enrollment(tenant_id, enrollment_id)
);

create table mk_journey_migration (
  tenant_id varchar(64) not null,
  migration_id varchar(64) not null,
  enrollment_id varchar(64) not null,
  from_version bigint not null,
  to_version bigint not null,
  previous_snapshot_json mediumtext not null,
  state_name varchar(32) not null,
  created_by varchar(128) not null,
  created_at varchar(40) not null,
  primary key (tenant_id, migration_id)
);

create table mk_journey_runtime_generation (
  tenant_id varchar(64) not null,
  environment_name varchar(32) not null,
  cell_id varchar(32) not null,
  namespace_name varchar(128) not null,
  generation_no bigint not null,
  release_key_id varchar(128) not null,
  manifest_json mediumtext not null,
  artifact_id varchar(160) not null,
  artifact_payload mediumblob not null,
  manifest_signature varchar(1000) not null,
  warmed_at varchar(40) not null,
  primary key (tenant_id, environment_name, cell_id, namespace_name, generation_no),
  constraint uq_journey_runtime_artifact unique (tenant_id, environment_name, cell_id, namespace_name, artifact_id)
);

create table mk_journey_runtime_slot (
  tenant_id varchar(64) not null,
  environment_name varchar(32) not null,
  cell_id varchar(32) not null,
  namespace_name varchar(128) not null,
  desired_generation bigint not null,
  activation_sequence bigint not null,
  directive_signature varchar(1000) not null,
  directive_json mediumtext not null,
  updated_at varchar(40) not null,
  primary key (tenant_id, environment_name, cell_id, namespace_name)
);

create table mk_journey_runtime_activation (
  tenant_id varchar(64) not null,
  environment_name varchar(32) not null,
  cell_id varchar(32) not null,
  namespace_name varchar(128) not null,
  activation_sequence bigint not null,
  generation_no bigint not null,
  directive_signature varchar(1000) not null,
  directive_json mediumtext not null,
  activated_at varchar(40) not null,
  primary key (tenant_id, environment_name, cell_id, namespace_name, activation_sequence),
  constraint fk_journey_activation_generation foreign key
    (tenant_id, environment_name, cell_id, namespace_name, generation_no)
    references mk_journey_runtime_generation
      (tenant_id, environment_name, cell_id, namespace_name, generation_no)
);

create table mk_journey_runtime_kill_switch (
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

create table mk_journey_output_receipt (
  source_topic varchar(249) not null,
  source_partition integer not null,
  source_offset bigint not null,
  tenant_id varchar(64) not null,
  enrollment_id varchar(64) not null,
  event_type varchar(64) not null,
  payload_hash varchar(64) not null,
  consumed_at varchar(40) not null,
  primary key (source_topic, source_partition, source_offset)
);
create index ix_journey_output_enrollment on mk_journey_output_receipt(tenant_id, enrollment_id, consumed_at);

create table mk_journey_dispatch_position (
  tenant_id varchar(64) not null,
  enrollment_id varchar(64) not null,
  last_sequence bigint not null,
  updated_at varchar(40) not null,
  primary key (tenant_id, enrollment_id)
);

create table mk_journey_dispatch_outbox (
  tenant_id varchar(64) not null,
  outbox_id varchar(64) not null,
  command_id varchar(64) not null,
  enrollment_id varchar(64) not null,
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
  primary key (tenant_id, outbox_id),
  constraint uq_journey_dispatch_command unique (tenant_id, command_id),
  constraint uq_journey_dispatch_sequence unique (tenant_id, enrollment_id, stream_sequence),
  constraint fk_dispatch_effect foreign key (tenant_id, command_id)
    references mk_node_effect_intent(tenant_id, command_id)
);
create index ix_journey_dispatch_pending on mk_journey_dispatch_outbox(published_at, dead_lettered_at, next_attempt_at, created_at);
