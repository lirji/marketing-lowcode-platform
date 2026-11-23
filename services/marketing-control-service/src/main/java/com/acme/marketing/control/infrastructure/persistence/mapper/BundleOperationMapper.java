package com.acme.marketing.control.infrastructure.persistence.mapper;

import com.acme.marketing.control.application.BundleOperationStore;
import org.apache.ibatis.annotations.Mapper;

/** 无内存命令索引，多实例统一以控制数据库作为恢复入口。 */
@Mapper
public interface BundleOperationMapper extends BundleOperationStore {}
