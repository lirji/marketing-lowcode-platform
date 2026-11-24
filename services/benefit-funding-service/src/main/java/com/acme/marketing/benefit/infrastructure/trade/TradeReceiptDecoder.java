package com.acme.marketing.benefit.infrastructure.trade;

import com.acme.marketing.platform.crypto.Digests;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

/** 从受控交易主题解码 V1 报文，校验 broker 路由键和交易源聚合一致后才生成可持久事实。 */
@Component
final class TradeReceiptDecoder {
    private static final String PRODUCER = "transaction-center";
    private static final String AGGREGATE_TYPE = "ORDER";
    private static final String REFUND_RESERVED = "REFUND_RESERVED";
    private static final String REFUND_RELEASED = "REFUND_RELEASED";
    private static final String REFUNDED = "REFUND_SUCCEEDED";
    private static final Set<String> REFUND_REASONS = Set.of(REFUND_RESERVED, REFUND_RELEASED, REFUNDED);
    private static final int MAX_PAYLOAD_BYTES = 16 * 1024;
    private static final long MAX_AMOUNT = 9_007_199_254_740_991L;

    private final ObjectReader reader;

    TradeReceiptDecoder(ObjectMapper json) {
        this.reader = json.reader().with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    /** 验证传输元数据和跨字段金额约束。失败不继续读取或保存原始载荷。 */
    TradeReceipt decode(ConsumerRecord<String, String> record, String topicPrefix) {
        try {
            return decodeV1(record, topicPrefix);
        } catch (JacksonException | IllegalArgumentException | DateTimeException malformed) {
            throw new InvalidTradeReceiptException(InvalidTradeReceiptException.INVALID);
        }
    }

    private TradeReceipt decodeV1(ConsumerRecord<String, String> record, String topicPrefix) {
        String payload = record.value();
        require(payload != null && payload.length() <= MAX_PAYLOAD_BYTES
                && payload.getBytes(StandardCharsets.UTF_8).length <= MAX_PAYLOAD_BYTES);
        JsonNode envelope = reader.readTree(payload);
        require(envelope != null && envelope.isObject());
        JsonNode data = envelope.path("data");
        String eventId = uuid(envelope, "eventId");
        String eventType = text(envelope, "eventType");
        String tenant = text(envelope, "tenantId");
        String order = text(envelope, "aggregateId");
        long evidenceVersion = integer(envelope, "aggregateVersion");
        Instant occurredAt = Instant.parse(text(envelope, "occurredAt"));
        uuid(envelope, "traceId");

        require(integer(envelope, "schemaVersion") == 1 && PRODUCER.equals(text(envelope, "producer"))
                && AGGREGATE_TYPE.equals(text(envelope, "aggregateType"))
                && occurredAt.equals(occurredAt.truncatedTo(ChronoUnit.MILLIS))
                && order.equals(text(data, "orderId"))
                && evidenceVersion > 0 && evidenceVersion == integer(data, "evidenceVersion")
                && record.topic().equals(topicPrefix + eventType)
                && (tenant + ":" + AGGREGATE_TYPE + ":" + order).equals(record.key())
                && eventId.equals(requiredEventHeader(record)));
        long tenantId = positiveId(tenant);
        long orderId = positiveId(order);
        positiveId(text(data, "memberId"));
        long paid = integer(data, "paidAmount");
        String currency = text(data, "currency");
        require(paid > 0 && paid <= MAX_AMOUNT && currency.matches("[A-Z]{3}"));

        String receipt;
        String reason = null;
        Long refund = null;
        Long cumulative = null;
        Long pending = null;
        if (TradeReceipt.ORDER_PAID.equals(eventType)) {
            receipt = text(data, "receiptNo");
            require(receipt.startsWith("RC-") && receipt.length() <= 64
                    && occurredAt.equals(Instant.parse(text(data, "paidAt")))
                    && "FIRST_CONFIRMED_PAID_ORDER_V1".equals(text(data, "firstOrderPolicy"))
                    && data.path("isFirstConfirmedPaidOrder").isBoolean());
        } else {
            require(TradeReceipt.ORDER_EVIDENCE_CHANGED.equals(eventType) || TradeReceipt.REFUND_SUCCEEDED.equals(eventType));
            receipt = text(data, "refundNo");
            reason = text(data, "changeReason");
            refund = integer(data, "refundAmount");
            cumulative = integer(data, "cumulativeRefundedAmount");
            pending = integer(data, "pendingRefundAmount");
            require(receipt.startsWith("RF-") && receipt.length() <= 64 && REFUND_REASONS.contains(reason)
                    && (!TradeReceipt.REFUND_SUCCEEDED.equals(eventType) || REFUNDED.equals(reason))
                    && refund > 0 && refund <= paid && cumulative >= 0 && cumulative <= paid
                    && pending >= 0 && pending <= paid - cumulative
                    && integer(data, "netPaidAmount") == paid - cumulative
                    && integer(data, "conservativeEligibleAmount") == paid - cumulative - pending
                    && (!REFUNDED.equals(reason) || cumulative >= refund));
        }
        return new TradeReceipt(eventId, eventType, tenantId, orderId, evidenceVersion, receipt,
                reason, currency, paid, refund, cumulative, pending, occurredAt.toString(),
                Digests.sha256Hex(payload), record.topic(), record.partition(), record.offset());
    }

    private static String requiredEventHeader(ConsumerRecord<String, String> record) {
        Iterator<Header> headers = record.headers().headers("eventId").iterator();
        require(headers.hasNext());
        byte[] value = headers.next().value();
        require(value != null && value.length == 36 && !headers.hasNext());
        return new String(value, StandardCharsets.US_ASCII);
    }

    private static long positiveId(String value) {
        require(value.matches("[1-9][0-9]{0,18}"));
        return Long.parseLong(value);
    }

    private static String uuid(JsonNode node, String field) {
        String value = text(node, field);
        require(UUID.fromString(value).toString().equals(value));
        return value;
    }

    private static long integer(JsonNode node, String field) {
        JsonNode value = node.path(field);
        require(value.isIntegralNumber() && value.canConvertToLong());
        return value.asLong();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        require(value.isString() && !value.asString().isBlank());
        return value.asString();
    }

    private static void require(boolean valid) {
        if (!valid) {
            throw new InvalidTradeReceiptException(InvalidTradeReceiptException.INVALID);
        }
    }
}
