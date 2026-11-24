package com.acme.marketing.benefit.interfaces;

import com.acme.marketing.benefit.application.TradeOrderBindingService;
import com.acme.marketing.benefit.application.TradeOrderBindingService.BindingView;
import com.acme.marketing.benefit.application.TradeOrderBindingService.BindRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 交易平台使用已验证 JWT 提交绑定，开发身份请求头不提供机器身份。 */
@RestController
@ConditionalOnProperty(name = "marketing.trade-order-bindings.enabled", havingValue = "true")
public class TradeOrderBindingController {
    private final TradeOrderBindingService service;

    /** 注入绑定用例，交易回执仍由独立监听器接收。 */
    public TradeOrderBindingController(TradeOrderBindingService service) {
        this.service = service;
    }

    /** 相同绑定重试返回首次审计结果，无需额外请求幂等键。 */
    @PutMapping("/internal/v1/promotion-applications/{applicationId}/trade-order-binding")
    public BindingView bind(@PathVariable String applicationId, @AuthenticationPrincipal Jwt jwt,
            @RequestBody BindRequest request) {
        return service.bind(applicationId, jwt == null ? null : jwt.getSubject(), request);
    }
}
