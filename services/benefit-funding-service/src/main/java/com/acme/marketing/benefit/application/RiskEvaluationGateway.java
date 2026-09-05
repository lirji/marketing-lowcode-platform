package com.acme.marketing.benefit.application;

import com.acme.marketing.platform.identity.TenantId;
import java.time.Instant;
import java.util.List;

/**
 * 发奖前风控端口。应用层只依赖稳定决策语义，不感知 risk-platform 的 HTTP 细节。
 */
public interface RiskEvaluationGateway {

    /** 使用服务端重算的金额和内部主体标识执行一次幂等风控评估。 */
    RiskDecision evaluate(RiskEvaluationRequest request);

    /** 发往 risk-platform 的内部请求；金额单位为最小货币单位且必须为正数。 */
    record RiskEvaluationRequest(TenantId tenantId, String transactionId, String accountNo,
            long amount, String currency, Instant eventTime) { }

    /** risk-platform 的稳定决策子集；营销读模型只保留必要字段。 */
    record RiskDecision(String decisionId, String action, List<String> hitRules) {
        public RiskDecision {
            hitRules = hitRules == null ? List.of() : List.copyOf(hitRules);
        }
    }

    /**
     * 超时、断路、容量耗尽、非成功 HTTP 或非法响应统一进入 fail-closed 分支。
     */
    final class RiskEvaluationUnavailableException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String reason;

        public RiskEvaluationUnavailableException(String reason, Throwable cause) {
            super(reason, cause);
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }
}
