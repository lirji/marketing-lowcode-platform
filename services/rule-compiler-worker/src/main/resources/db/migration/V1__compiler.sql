create table mk_compiled_artifact (
  tenant_id varchar(64) not null,
  artifact_id varchar(128) not null,
  definition_id varchar(128) not null,
  definition_version bigint not null,
  type_name varchar(64) not null,
  abi varchar(128) not null,
  payload mediumblob not null,
  checksum varchar(80) not null,
  source_digest varchar(80) not null,
  signature_key_id varchar(128) not null,
  signature_value varchar(256) not null,
  metadata_json text not null,
  compiled_at varchar(40) not null,
  primary key (tenant_id, artifact_id)
);
create index ix_compiled_artifact_source on mk_compiled_artifact(tenant_id, definition_id, definition_version, compiled_at);
