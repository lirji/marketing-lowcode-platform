create table mk_attribution_credit (
  tenant_id varchar(64) not null,
  conversion_event_id varchar(128) not null,
  policy_name varchar(32) not null,
  touch_event_id varchar(128) not null,
  credit_value decimal(20,8) not null,
  revenue_minor bigint not null,
  calculated_at varchar(40) not null,
  primary key (tenant_id, conversion_event_id, policy_name, touch_event_id)
);
create index ix_attribution_credit_time on mk_attribution_credit(tenant_id, calculated_at, policy_name);

create table mk_attribution_recompute_lock (
  tenant_id varchar(64) not null,
  updated_at varchar(40) not null,
  primary key (tenant_id)
);

create table mk_measurement_command (
  tenant_id varchar(64) not null,
  command_id varchar(128) not null,
  payload_hash varchar(64) not null,
  state_name varchar(32) not null,
  response_json mediumtext,
  created_at varchar(40) not null,
  expires_at varchar(40) not null,
  primary key (tenant_id, command_id)
);
create index ix_measurement_command_expiry on mk_measurement_command(expires_at);
