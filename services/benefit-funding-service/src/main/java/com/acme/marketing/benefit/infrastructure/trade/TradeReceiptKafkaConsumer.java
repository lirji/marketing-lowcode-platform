package com.acme.marketing.benefit.infrastructure.trade;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** 只接纳配置的交易 V1 主题，交易租户仍保持源身份，不在消费时推断营销绑定。 */
@Component
@ConditionalOnProperty(name = "marketing.trade-receipts.enabled", havingValue = "true")
class TradeReceiptKafkaConsumer {
    private final TradeReceiptDecoder decoder;
    private final TradeReceiptInbox inbox;
    private final String topicPrefix;

    TradeReceiptKafkaConsumer(TradeReceiptDecoder decoder, TradeReceiptInbox inbox,
            @Value(TradeReceipt.TOPIC_PREFIX) String topicPrefix) {
        this.decoder = decoder;
        this.inbox = inbox;
        this.topicPrefix = topicPrefix;
    }

    /** Spring 容器只在入箱事务已经提交或确认原事件重放后推进源记录位点。 */
    @KafkaListener(containerFactory = "tradeReceiptListenerFactory",
            topics = {TradeReceipt.TOPIC_PREFIX + TradeReceipt.ORDER_PAID,
                    TradeReceipt.TOPIC_PREFIX + TradeReceipt.ORDER_EVIDENCE_CHANGED,
                    TradeReceipt.TOPIC_PREFIX + TradeReceipt.REFUND_SUCCEEDED},
            groupId = "${marketing.trade-receipts.consumer-group:mk-trade-receipts-v1}")
    public void consume(ConsumerRecord<String, String> record) {
        inbox.record(decoder.decode(record, topicPrefix));
    }
}
