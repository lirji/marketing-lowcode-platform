package com.acme.marketing.benefit.application;

/** 保存已验证的交易订单关联，并读取资金服务自身的交易回执入箱。 */
public interface TradeOrderBindingRepository {
    /** 读取首次绑定，供调用方在重试时返回相同的审计凭据。 */
    Binding findBinding(ApplicationKey key);

    /** 取该原生订单已入箱的最高证据版本，避免跨主题投递顺序决定绑定凭据。 */
    ReceiptEvidence findReceipt(SourceOrder order);

    /** 每个营销租户下的资金申请只能保存一次绑定。 */
    int insertBinding(Binding binding);

    record ApplicationKey(String tenantId, String applicationId) { }
    record SourceOrder(long sourceTenantId, long sourceOrderId) { }
    record ReceiptEvidence(String eventId, long evidenceVersion, String currency) { }
    record Binding(String tenantId, String applicationId, long sourceTenantId, long sourceOrderId,
            String receiptEventId, long evidenceVersion, String boundBy, String boundAt) { }
}
