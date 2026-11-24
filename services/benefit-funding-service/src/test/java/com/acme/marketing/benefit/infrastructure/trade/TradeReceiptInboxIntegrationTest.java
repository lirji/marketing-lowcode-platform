package com.acme.marketing.benefit.infrastructure.trade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.acme.marketing.testsupport.MySqlIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class TradeReceiptInboxIntegrationTest extends MySqlIntegrationTest {
    @Autowired TradeReceiptDecoder decoder;
    @Autowired TradeReceiptInbox inbox;
    @Autowired JdbcTemplate jdbc;
    @Autowired ApplicationContext context;

    @Test
    void duplicateDeliveryPersistsOnlyOriginalOffsetAndRejectsDifferentPayload() throws Exception {
        String id = UUID.randomUUID().toString();
        inbox.record(decoder.decode(payment(id, 10, "110:ORDER:701", 100), "it."));
        try (var pool = Executors.newFixedThreadPool(4)) {
            var jobs = java.util.stream.LongStream.range(11, 15).mapToObj(offset ->
                    (java.util.concurrent.Callable<Void>) () -> {
                        inbox.record(decoder.decode(payment(id, offset, "110:ORDER:701", 100), "it."));
                        return null;
                    }).toList();
            for (var attempt : pool.invokeAll(jobs, 12, TimeUnit.SECONDS)) {
                attempt.get();
            }
        }
        var conflict = assertThrows(InvalidTradeReceiptException.class,
                () -> inbox.record(decoder.decode(payment(id, 15, "110:ORDER:701", 101), "it.")));
        assertEquals(InvalidTradeReceiptException.EVENT_CONFLICT, conflict.getMessage());
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM mk_trade_receipt_inbox WHERE event_id=?", Integer.class, id));
        var result = jdbc.queryForMap(
                "SELECT paid_minor,kafka_offset,source_order_id FROM mk_trade_receipt_inbox WHERE event_id=?", id);
        assertEquals(List.of(100L, 10L, 701L),
                List.of(result.get("paid_minor"), result.get("kafka_offset"), result.get("source_order_id")));
    }

    @Test
    void rejectsWrongPartitionKeyAndListenerIsDisabledByDefault() {
        var record = payment(UUID.randomUUID().toString(), 20, "120:ORDER:701", 100);
        assertThrows(InvalidTradeReceiptException.class, () -> decoder.decode(record, "it."));
        assertTrue(context.getBeansOfType(TradeReceiptKafkaConsumer.class).isEmpty());
    }

    private static ConsumerRecord<String, String> payment(String id, long offset, String key, long amount) {
        String payload = """
                {"eventId":"%s","eventType":"trade.order-paid.v1","schemaVersion":1,"tenantId":"110",
                 "aggregateType":"ORDER","aggregateId":"701","aggregateVersion":3,
                 "occurredAt":"2026-11-24T03:20:30.123Z","producer":"transaction-center",
                 "traceId":"4f501af5-4c60-4b65-a69f-a71afcc72972",
                 "data":{"orderId":"701","memberId":"3","receiptNo":"RC-sandbox","paidAmount":%d,"currency":"CNY",
                         "paidAt":"2026-11-24T03:20:30.123Z","firstOrderPolicy":"FIRST_CONFIRMED_PAID_ORDER_V1",
                         "isFirstConfirmedPaidOrder":true,"evidenceVersion":3}}
                """.formatted(id, amount);
        var record = new ConsumerRecord<>("it." + TradeReceipt.ORDER_PAID, 0, offset, key, payload);
        record.headers().add("eventId", id.getBytes(StandardCharsets.US_ASCII));
        return record;
    }
}
