create table mk_benefit_definition (
  tenant_id varchar(64) not null,
  benefit_id varchar(128) not null,
  version_no bigint not null,
  name_text varchar(256) not null,
  status_name varchar(32) not null,
  resource_key varchar(256) not null,
  policy_json mediumtext not null,
  created_by varchar(128) not null,
  created_at varchar(40) not null,
  primary key (tenant_id, benefit_id, version_no)
);
create index ix_benefit_catalog_latest on mk_benefit_definition(tenant_id, benefit_id, version_no);

create table mk_benefit_definition_head (
  tenant_id varchar(64) not null,
  benefit_id varchar(128) not null,
  latest_version bigint not null,
  primary key (tenant_id, benefit_id)
);
