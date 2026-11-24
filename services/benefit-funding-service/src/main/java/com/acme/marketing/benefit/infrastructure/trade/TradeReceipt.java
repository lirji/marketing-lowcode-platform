package com.acme.marketing.benefit.infrastructure.trade;

/** 保存后续核对所需的原生交易事实。原始报文、会员标识和营销归属都不入箱。 */
public record TradeReceipt(String eventId, String eventType, long sourceTenantId, long sourceOrderId,
        long evidenceVersion, String receiptReference, String changeReason, String currency, long paidMinor,
        Long refundMinor, Long cumulativeRefundedMinor, Long pendingRefundMinor, String occurredAt,
        String payloadDigest, String topic, int partition, long offset) {
    static final String ORDER_PAID = "trade.order-paid.v1";
    static final String ORDER_EVIDENCE_CHANGED = "trade.order-evidence-changed.v1";
    static final String REFUND_SUCCEEDED = "trade.refund-succeeded.v1";
    static final String TOPIC_PREFIX = "${marketing.trade-receipts.topic-prefix:}";
}
