package com.acme.marketing.benefit.infrastructure.trade;

import com.acme.marketing.benefit.infrastructure.persistence.mapper.TradeReceiptMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 单次提交保存原生交易证据和幂等指纹，供 Kafka 在返回后确认位点。 */
@Service
class TradeReceiptInbox {
    private final TradeReceiptMapper mapper;

    TradeReceiptInbox(TradeReceiptMapper mapper) {
        this.mapper = mapper;
    }

    /** 同事件相同原字节可以重放。异载荷保持原记录，并让监听器隔离冲突。 */
    @Transactional
    public void record(TradeReceipt receipt) {
        mapper.reserve(receipt);
        if (!receipt.payloadDigest().equals(mapper.payloadDigestForUpdate(receipt.eventId()))) {
            throw new InvalidTradeReceiptException(InvalidTradeReceiptException.EVENT_CONFLICT);
        }
    }
}
