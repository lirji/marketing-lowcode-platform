package com.acme.marketing.benefit.application;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** 权益中心订单只读端口；订单身份由已持久化的原发奖请求确定。 */
public interface AwardFulfillmentGateway {
    /** 查询权威订单；不存在和暂不可用都不能解释为取消或最终失败。 */
    Order query(String tenantId, String sourceSystem, String sourceRequestId);

    /** 上游订单身份仅在内部核对，recipientRef 不进入运营读模型或诊断。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Order(String orderNo, String sourceSystem, String sourceRequestId, String recipientRef,
            String status, List<Item> items) {
        public Order { items = List.copyOf(items); }
        @Override public String toString() { return "BenefitOrder[redacted]"; }
    }
    /** 稳定项身份用于防止把另一张订单或另一版 SKU 的状态写入本地。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Item(String clientItemId, String skuId, String benefitType, long quantity,
            Long amountMinor, String currency, Long skuVersion, String status) { }

    /** 仅携带稳定原因码，不包含远程地址、响应正文、凭据或主体。 */
    final class UnavailableException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String code;
        public UnavailableException(String code) { super(code); this.code = code; }
        public String code() { return code; }
    }
}
