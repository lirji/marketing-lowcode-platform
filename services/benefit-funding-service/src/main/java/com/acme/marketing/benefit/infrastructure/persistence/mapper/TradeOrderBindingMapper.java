package com.acme.marketing.benefit.infrastructure.persistence.mapper;

import com.acme.marketing.benefit.application.TradeOrderBindingRepository;
import org.apache.ibatis.annotations.Mapper;

/** 用 XML 查询真实入箱证据和保存绑定，应用层保留独立的持久化接口。 */
@Mapper
public interface TradeOrderBindingMapper extends TradeOrderBindingRepository { }
