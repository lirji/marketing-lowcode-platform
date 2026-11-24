package com.acme.marketing.benefit.infrastructure.persistence.mapper;

import com.acme.marketing.benefit.infrastructure.trade.TradeReceipt;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 交易回执入箱通过事件主键串行同一事件的重复投递。 */
@Mapper
public interface TradeReceiptMapper {
    /** 首次插入，重放不替换原事实或原 Kafka 位点。 */
    int reserve(TradeReceipt receipt);

    /** 当前读保证并发重放看到此前持有主键的事务提交的指纹。 */
    String payloadDigestForUpdate(@Param("eventId") String eventId);
}
