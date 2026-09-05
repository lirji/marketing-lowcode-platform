package com.acme.marketing.benefit.interfaces;

import com.acme.marketing.benefit.application.BenefitFundingService;
import com.acme.marketing.benefit.application.BenefitSkuCatalog.BenefitSkuView;
import com.acme.marketing.benefit.application.BenefitSkuCatalog.SkuStatus;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PutMapping;

@RestController
@RequestMapping("/api/v1")
public class BenefitController {
    private final BenefitFundingService service;

    public BenefitController(BenefitFundingService service) { this.service = service; }

    @PostMapping("/funding/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    public BenefitFundingService.AccountView createAccount(
            @Valid @RequestBody BenefitFundingService.CreateAccountRequest request) {
        return service.createAccount(request);
    }

    @GetMapping("/funding/accounts")
    public List<BenefitFundingService.AccountView> accounts() {
        return service.accounts();
    }

    @GetMapping("/benefits")
    public List<BenefitFundingService.BenefitView> benefits() {
        return service.benefits();
    }

    @GetMapping("/benefits/{benefitId}")
    public BenefitFundingService.BenefitView benefit(@PathVariable String benefitId) {
        return service.benefit(benefitId);
    }

    /** 返回租户隔离的权益中台 SKU 只读目录，默认只查询 ACTIVE 模板。 */
    @GetMapping("/benefit-skus")
    public List<BenefitSkuView> benefitSkus(
            @RequestParam(defaultValue = "ACTIVE") SkuStatus status) {
        return service.benefitSkus(status);
    }

    @PutMapping("/benefits/{benefitId}")
    public BenefitFundingService.BenefitView putBenefit(@PathVariable String benefitId,
            @RequestHeader("Idempotency-Key") String commandId,
            @RequestBody BenefitFundingService.BenefitRequest request) {
        return service.putBenefit(benefitId, commandId, request);
    }

    @PostMapping("/funding/accounts/{resourceKey}:advance-fence")
    public BenefitFundingService.AccountView advanceFence(@PathVariable String resourceKey,
            @Valid @RequestBody BenefitFundingService.FencingLeaseRequest request) {
        return service.advanceFence(resourceKey, request);
    }

    @PostMapping("/promotion-applications")
    @ResponseStatus(HttpStatus.CREATED)
    public BenefitFundingService.ApplicationView reserve(@RequestHeader("Idempotency-Key") String commandId,
            @Valid @RequestBody BenefitFundingService.ReserveRequest request) {
        return service.reserve(commandId, request);
    }

    @PostMapping("/promotion-applications/{applicationId}:confirm")
    public BenefitFundingService.ApplicationView confirm(@PathVariable String applicationId,
            @RequestHeader("Idempotency-Key") String commandId,
            @Valid @RequestBody BenefitFundingService.SettlementRequest request) {
        return service.confirm(applicationId, commandId, request);
    }

    @PostMapping("/promotion-applications/{applicationId}:cancel")
    public BenefitFundingService.ApplicationView cancel(@PathVariable String applicationId,
            @RequestHeader("Idempotency-Key") String commandId,
            @Valid @RequestBody BenefitFundingService.SettlementRequest request) {
        return service.cancel(applicationId, commandId, request);
    }

    @PostMapping("/promotion-applications/{applicationId}:refund")
    public BenefitFundingService.ApplicationView refund(@PathVariable String applicationId,
            @RequestHeader("Idempotency-Key") String commandId,
            @Valid @RequestBody BenefitFundingService.RefundRequest request) {
        return service.refund(applicationId, commandId, request);
    }

    @PostMapping("/promotion-applications/{applicationId}:reverse")
    public BenefitFundingService.ApplicationView reverse(@PathVariable String applicationId,
            @RequestHeader("Idempotency-Key") String commandId,
            @Valid @RequestBody BenefitFundingService.SettlementRequest request) {
        return service.reverse(applicationId, commandId, request);
    }

    @PostMapping("/promotion-applications:expire")
    public BenefitFundingService.ExpirationResult expire(
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "100") int limit) {
        return service.expireReservations(limit);
    }

    @GetMapping("/funding/reconciliation")
    public BenefitFundingService.ReconciliationReport reconcile() { return service.reconcile(); }
}
