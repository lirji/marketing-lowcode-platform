create table mk_resource_account (
  tenant_id varchar(64) not null,
  resource_key varchar(256) not null,
  resource_type varchar(32) not null,
  currency_code varchar(8) not null,
  authorized_amount bigint not null,
  available_amount bigint not null,
  reserved_amount bigint not null,
  consumed_amount bigint not null,
  returned_amount bigint not null,
  fencing_epoch bigint not null,
  version_no bigint not null,
  state_name varchar(32) not null,
  updated_at varchar(40) not null,
  primary key (tenant_id, resource_key),
  constraint ck_resource_nonnegative check
    (authorized_amount >= 0 and available_amount >= 0 and reserved_amount >= 0
      and consumed_amount >= 0 and returned_amount >= 0 and fencing_epoch >= 1),
  constraint ck_resource_conservation check
    (authorized_amount = available_amount + reserved_amount + consumed_amount)
);

create table mk_promotion_application (
  tenant_id varchar(64) not null,
  application_id varchar(64) not null,
  quote_id varchar(128) not null,
  decision_request_id varchar(128) not null,
  order_id varchar(128) not null,
  organization_id varchar(128) not null,
  shop_ids_json text not null,
  cart_digest varchar(80) not null,
  token_digest varchar(64) not null,
  generation_no bigint not null,
  state_name varchar(32) not null,
  total_discount bigint not null,
  currency_code varchar(8) not null,
  expires_at varchar(40) not null,
  created_at varchar(40) not null,
  updated_at varchar(40) not null,
  expiry_attempts integer not null default 0,
  expiry_next_attempt_at varchar(40) not null,
  expiry_last_error varchar(1000) not null default '',
  primary key (tenant_id, application_id),
  constraint uq_application_quote unique (tenant_id, quote_id)
);
create index ix_application_order on mk_promotion_application(tenant_id, order_id, state_name);
create index ix_application_expiry on mk_promotion_application(tenant_id, state_name, expires_at, expiry_next_attempt_at);

create table mk_reservation_item (
  tenant_id varchar(64) not null,
  application_id varchar(64) not null,
  resource_key varchar(256) not null,
  resource_type varchar(32) not null,
  currency_code varchar(8) not null,
  original_amount bigint not null,
  reserved_amount bigint not null,
  consumed_amount bigint not null,
  refunded_amount bigint not null,
  released_amount bigint not null,
  reservation_epoch bigint not null,
  primary key (tenant_id, application_id, resource_key),
  constraint fk_reservation_application foreign key (tenant_id, application_id)
    references mk_promotion_application(tenant_id, application_id),
  constraint fk_reservation_resource foreign key (tenant_id, resource_key)
    references mk_resource_account(tenant_id, resource_key),
  constraint ck_reservation_nonnegative check
    (original_amount > 0 and reserved_amount >= 0 and consumed_amount >= 0
      and refunded_amount >= 0 and released_amount >= 0 and reservation_epoch >= 1),
  constraint ck_reservation_conservation check
    (original_amount = reserved_amount + consumed_amount + refunded_amount + released_amount)
);

create table mk_funding_ledger (
  tenant_id varchar(64) not null,
  ledger_id varchar(64) not null,
  application_id varchar(64) not null,
  order_id varchar(128) not null,
  resource_key varchar(256) not null,
  operation_name varchar(32) not null,
  debit_bucket varchar(32) not null,
  credit_bucket varchar(32) not null,
  amount_value bigint not null,
  account_version bigint not null,
  correction_of varchar(64),
  occurred_at varchar(40) not null,
  primary key (tenant_id, ledger_id),
  constraint ck_ledger_positive check (amount_value > 0)
);
create index ix_ledger_application on mk_funding_ledger(tenant_id, application_id, occurred_at);
create index ix_ledger_resource on mk_funding_ledger(tenant_id, resource_key, occurred_at);
create unique index uq_ledger_resource_version on mk_funding_ledger(tenant_id, resource_key, account_version);

create table mk_command_dedup (
  tenant_id varchar(64) not null,
  command_id varchar(128) not null,
  payload_hash varchar(64) not null,
  state_name varchar(32) not null,
  response_json mediumtext,
  created_at varchar(40) not null,
  expires_at varchar(40) not null,
  primary key (tenant_id, command_id)
);

create table mk_benefit_outbox (
  tenant_id varchar(64) not null,
  event_id varchar(64) not null,
  aggregate_id varchar(64) not null,
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
  constraint uq_benefit_outbox_sequence unique (tenant_id, aggregate_id, stream_sequence)
);
create index ix_benefit_outbox_pending on mk_benefit_outbox(published_at, dead_lettered_at, next_attempt_at, created_at);

create table mk_benefit_outbox_position (
  tenant_id varchar(64) not null,
  aggregate_id varchar(64) not null,
  last_sequence bigint not null,
  updated_at varchar(40) not null,
  primary key (tenant_id, aggregate_id)
);

create table mk_journey_benefit_grant (
  tenant_id varchar(64) not null,
  command_id varchar(128) not null,
  enrollment_id varchar(64) not null,
  subject_token varchar(256) not null,
  journey_id varchar(128) not null,
  journey_version bigint not null,
  resource_key varchar(256) not null,
  benefit_id varchar(256) not null,
  quantity_value bigint not null,
  fencing_epoch bigint not null,
  state_name varchar(32) not null,
  created_at varchar(40) not null,
  primary key (tenant_id, command_id),
  constraint fk_journey_grant_resource foreign key (tenant_id, resource_key)
    references mk_resource_account(tenant_id, resource_key),
  constraint ck_journey_grant_positive check (quantity_value > 0 and fencing_epoch > 0)
);
create index ix_journey_grant_subject on mk_journey_benefit_grant(tenant_id, subject_token, created_at);
