create table mk_release_bundle (
  tenant_id varchar(64) not null comment '可信身份中的租户编号',
  bundle_id varchar(64) not null comment '不可变活动组合编号',
  campaign_id varchar(64) not null comment '归属活动编号，继承组织和店铺访问范围',
  version_no bigint not null comment '活动内递增版本，从一开始且不可覆盖',
  environment_name varchar(32) not null comment '组合成员共同的目标环境',
  cell_id varchar(64) not null comment '组合成员共同的运行区域',
  state_name varchar(32) not null comment '组合状态，首期仅PREPARING且不得激活',
  content_json mediumtext not null comment '冻结的完整组合及成员签名清单，最大512KiB',
  content_hash varchar(64) not null comment '租户活动与有序成员签名的SHA256摘要',
  command_id varchar(128) not null comment '首次准备本版本的幂等命令键',
  created_by varchar(128) not null comment '可信身份中的准备人编号',
  created_at varchar(40) not null comment 'UTC创建时间，使用平台统一格式',
  primary key (tenant_id, bundle_id),
  unique key uk_release_bundle_version (tenant_id, campaign_id, version_no),
  constraint ck_release_bundle_version check (version_no > 0),
  constraint fk_release_bundle_campaign foreign key (tenant_id, campaign_id)
    references mk_campaign(tenant_id, campaign_id)
) comment '活动组合不可变准备版本；准备成功不代表运行端已就绪或已生效';

alter table mk_release_manifest
  add column bundle_id varchar(64) null comment '不可变归属组合编号；非空时禁止单清单激活或回滚',
  add key idx_manifest_bundle (tenant_id, bundle_id),
  add constraint fk_manifest_release_bundle foreign key (tenant_id, bundle_id)
    references mk_release_bundle(tenant_id, bundle_id);

alter table mk_campaign
  add column release_bundle_version bigint not null default 0 comment '组合版本分配游标；活动行锁内递增，与组合及审计同事务提交',
  add constraint ck_campaign_release_bundle_version check (release_bundle_version >= 0);
