package com.acme.marketing.benefit.infrastructure.trade;

/** 永久拒绝原因只使用稳定代码，避免把事件报文带入异常日志。 */
final class InvalidTradeReceiptException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    static final String INVALID = "TRADE_RECEIPT_INVALID";
    static final String EVENT_CONFLICT = "TRADE_RECEIPT_EVENT_CONFLICT";

    InvalidTradeReceiptException(String code) {
        super(code);
    }
}
