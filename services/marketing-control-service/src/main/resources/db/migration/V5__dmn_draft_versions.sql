create table mk_dmn_draft_version (
  tenant_id varchar(64) not null comment '可信身份中的租户编号',
  campaign_id varchar(64) not null comment '归属活动编号，沿用活动组织和店铺访问范围',
  version_no bigint not null comment '活动内不可变递增草稿版本，从一开始',
  table_json mediumtext not null comment '有界结构化草稿，允许未通过语义分析的编辑内容',
  content_hash varchar(64) not null comment '规范记录序列化后的SHA256内容摘要',
  command_id varchar(128) not null comment '首次保存本版本的幂等命令键',
  created_by varchar(128) not null comment '可信身份中的保存人编号',
  created_at varchar(40) not null comment 'UTC保存时间，使用平台统一时间格式',
  primary key (tenant_id, campaign_id, version_no),
  constraint ck_dmn_draft_version_positive check (version_no > 0),
  constraint fk_dmn_draft_campaign foreign key (tenant_id, campaign_id)
    references mk_campaign(tenant_id, campaign_id)
) comment '活动DMN不可变草稿版本；保存不代表审核通过或发布生效';
