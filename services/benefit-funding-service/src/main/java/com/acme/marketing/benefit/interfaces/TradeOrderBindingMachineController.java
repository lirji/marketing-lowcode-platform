package com.acme.marketing.benefit.interfaces;

import com.acme.marketing.benefit.application.TradeOrderBindingMachineAuthority;
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

/** 单独接收 Casdoor 应用令牌，保持普通营销 API 使用原有租户安全链。 */
@RestController
@ConditionalOnProperty(name = {"marketing.trade-order-bindings.enabled",
        "marketing.trade-order-bindings.casdoor.enabled"}, havingValue = "true")
@ConditionalOnProperty(name = "marketing.security.mode", havingValue = "OIDC")
public class TradeOrderBindingMachineController {
    static final String PATH = "/internal/v1/promotion-applications/{applicationId}/trade-order-binding/casdoor";

    private final TradeOrderBindingService bindings;
    private final TradeOrderBindingMachineAuthority authority;

    /** 注入专用授权表及共用绑定事务。 */
    public TradeOrderBindingMachineController(TradeOrderBindingService bindings,
            TradeOrderBindingMachineAuthority authority) {
        this.bindings = bindings;
        this.authority = authority;
    }

    /** 授权来源是服务端配置，调用方仍需提交同一原生订单的原始签名报价。 */
    @PutMapping(PATH)
    public BindingView bind(@PathVariable String applicationId, @AuthenticationPrincipal Jwt jwt,
            @RequestBody BindRequest request) {
        return bindings.bindMachine(applicationId, authority.authorize(jwt), request);
    }
}
