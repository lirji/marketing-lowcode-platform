package com.acme.marketing.referral.infrastructure.persistence.mapper;

import com.acme.marketing.referral.application.query.ReferralOperationsRepository;
import org.apache.ibatis.annotations.Mapper;

/** 使用 Mapper XML 读取裂变主库，投影与分页由查询端口定义。 */
@Mapper
public interface ReferralOperationsMapper extends ReferralOperationsRepository {}
