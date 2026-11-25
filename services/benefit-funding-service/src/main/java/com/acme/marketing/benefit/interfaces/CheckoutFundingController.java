package com.acme.marketing.benefit.interfaces;

import com.acme.marketing.benefit.application.CheckoutFundingService;
import com.acme.marketing.platform.web.TenantContextHolder;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 机器按原交易报价取得预算回执及核对资金终态。 */
@RestController
@ConditionalOnProperty(name = "marketing.checkout-funding.enabled", havingValue = "true")
public class CheckoutFundingController {
    private static final String BASE = "/api/v1/promotion-applications/checkout-pilot";
    private final CheckoutFundingService service;

    /** 只从受信应用服务读取或生成回执。 */
    public CheckoutFundingController(CheckoutFundingService service) { this.service = service; }

    /** 相同报价和证明从提交记录中重放，预算凭证不进入缓存。 */
    @PostMapping(BASE + "/{reference}:reserve")
    public ResponseEntity<CheckoutFundingService.Reservation> reserve(@PathVariable String reference,
            @Valid @RequestBody Proof request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.reserve(TenantContextHolder.requireCurrent(), reference, request.checkoutQuoteToken()));
    }

    /** 结算前向交易核对当前状态，调用方不能自行声明付款或取消。 */
    @PostMapping(BASE + "/{reference}:reconcile")
    public ResponseEntity<CheckoutFundingService.Reservation> reconcile(@PathVariable String reference) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.reconcile(TenantContextHolder.requireCurrent(), reference));
    }

    /** 只接收原始签价，订单和预算状态由服务端查询。 */
    public record Proof(@NotBlank @Size(max = CheckoutFundingService.MAX_PROOF) String checkoutQuoteToken) { }
}
