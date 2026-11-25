package com.acme.marketing.decision.interfaces;

import com.acme.marketing.decision.application.CheckoutPilotAuthority;
import com.acme.marketing.decision.application.DecisionApplicationService.DecisionRequest;
import com.acme.marketing.decision.application.DecisionApplicationService.DecisionResponse;
import com.acme.marketing.decision.application.DecisionRequestExecutor;
import com.acme.marketing.platform.web.TenantContextHolder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 显式试点入口复用已激活组合和幂等账，普通报价入口不签交易价格证明。 */
@RestController
@ConditionalOnProperty(name = "marketing.checkout-pilot.enabled", havingValue = "true")
public class CheckoutPilotDecisionController {
    private final CheckoutPilotAuthority authority;
    private final DecisionRequestExecutor executor;

    public CheckoutPilotDecisionController(CheckoutPilotAuthority authority, DecisionRequestExecutor executor) {
        this.authority = authority;
        this.executor = executor;
    }

    /** 重放前同样确认机器、门店及报价有效期。 */
    @PostMapping("/api/v1/decisions/checkout-pilot:evaluate")
    public DecisionResponse evaluate(@RequestBody DecisionRequest request) {
        var scope = TenantContextHolder.requireCurrent();
        authority.require(scope, request);
        return executor.execute(scope, request);
    }
}
