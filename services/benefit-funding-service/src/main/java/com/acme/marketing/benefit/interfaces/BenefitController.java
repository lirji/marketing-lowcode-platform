package com.acme.marketing.benefit.interfaces;

import com.acme.marketing.benefit.application.BenefitFundingService;
import com.acme.marketing.benefit.application.BenefitSkuCatalog.BenefitSkuView;
import com.acme.marketing.benefit.application.BenefitSkuCatalog.SkuStatus;
import com.acme.marketing.platform.web.PersistentIdempotentCommandExecutor;
import com.acme.marketing.platform.web.TenantContextHolder;
import jakarta.validation.Valid;
import java.time.Instant;
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
    private final PersistentIdempotentCommandExecutor commands;

    public BenefitController(BenefitFundingService service, PersistentIdempotentCommandExecutor commands) {
        this.service = service;
        this.commands = commands;
    }

    @PostMapping("/funding/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    public BenefitFundingService.AccountView createAccount(
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody BenefitFundingService.CreateAccountRequest request) {
        return commands.execute(TenantContextHolder.requireCurrent().tenantId(), "benefit.account.create",
                key, request, BenefitFundingService.AccountView.class, () -> service.createAccount(request));
    }

    @GetMapping("/funding/accounts")
    public List<BenefitFundingService.AccountView> accounts() {
        return service.accounts();
    }

    @GetMapping("/benefits")
    public List<BenefitFundingService.BenefitView> benefits() {
        return service.benefits();
    }

    /** 供资产目录进行完整服务端搜索和有界分页，授权复用权益只读权限。 */
    @GetMapping("/benefits/catalog/page")
    public BenefitFundingService.BenefitPage benefitPage(@RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String q, @RequestParam(required = false) String cursor) {
        return service.benefitPage(limit, q, cursor);
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

    /** 精确读取原生交易和营销申请的可信关联以及资金留痕。 */
    @GetMapping("/funding/trade-orders/{tradeTenant}/{nativeOrderId}/operation-audits")
    public BenefitFundingService.TradeOrderAudit tradeOrderAudits(@PathVariable String tradeTenant,
            @PathVariable String nativeOrderId, @RequestParam(required = false) String cursor) {
        return service.tradeOrderAudits(tradeTenant, nativeOrderId, cursor);
    }

    @PostMapping("/promotion-applications")
    @ResponseStatus(HttpStatus.CREATED)
    public BenefitFundingService.ApplicationView reserve(@RequestHeader("Idempotency-Key") String commandId,
            @Valid @RequestBody BenefitFundingService.ReserveRequest request) {
        return service.reserve(commandId, request);
    }

    /** 查看授权申请的原交易、资金终态和重试引用。 */
    @GetMapping("/promotion-applications/{applicationId}/operation-audits")
    public com.acme.marketing.benefit.application.FundingOperationAudit.History operationAudits(
            @PathVariable String applicationId, @RequestParam(required = false) String cursor) {
        return service.operationAudits(applicationId, cursor);
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

    /** 单笔申请使用原始签名报价恢复活动归属。重试以资金申请和报价摘要为边界。 */
    @PostMapping("/promotion-applications/{applicationId}:attribute-campaign")
    public BenefitFundingService.SubsidyAttribution attributeSubsidyCampaign(@PathVariable String applicationId,
            @RequestBody BenefitFundingService.SubsidyAttributionRequest request) {
        return service.attributeSubsidyCampaign(applicationId, request);
    }

    @PostMapping("/promotion-applications:expire")
    public BenefitFundingService.ExpirationResult expire(
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "100") int limit) {
        return service.expireReservations(limit);
    }

    @GetMapping("/funding/reconciliation")
    public BenefitFundingService.ReconciliationReport reconcile() { return service.reconcile(); }

    /** 逐笔提供已入账的补贴确认与退款，供运营和报表侧核对。 */
    @GetMapping("/funding/subsidy-postings")
    public BenefitFundingService.SubsidyPostingPage subsidyPostings(@RequestParam Instant from, @RequestParam Instant to,
            @RequestParam(required = false) String campaignId, @RequestParam(defaultValue = "false") boolean unassigned,
            @RequestParam(required = false) Integer limit, @RequestParam(required = false) String cursor) {
        return service.subsidyPostings(from, to, campaignId, unassigned, limit, cursor);
    }

    /** 订单号只限定已有入账记录，入口不创建资金申请或退款。 */
    @GetMapping("/funding/subsidy-postings/by-order")
    public BenefitFundingService.OrderSubsidyPostingPage subsidyPostingsByOrder(@RequestParam String orderId,
            @RequestParam Instant from, @RequestParam Instant to, @RequestParam(required = false) String campaignId,
            @RequestParam(defaultValue = "false") boolean unassigned, @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        return service.subsidyPostingsByOrder(orderId, from, to, campaignId, unassigned, limit, cursor);
    }

    /** 资金对账人员可在补贴入账前查看订单资金申请的绑定证据。 */
    @GetMapping("/funding/subsidy-postings/by-order/bindings")
    public BenefitFundingService.OrderBindingEvidencePage orderBindingEvidence(@RequestParam String orderId,
            @RequestParam(required = false) String campaignId, @RequestParam(defaultValue = "false") boolean unassigned,
            @RequestParam(required = false) String after,
            @RequestParam(defaultValue = "false") boolean includeCheckoutBinding) {
        return service.orderBindingEvidence(orderId, campaignId, unassigned, after, includeCheckoutBinding);
    }

    /** 对账汇总继承流水读取权限，并返回实际采用的范围与币种。 */
    @GetMapping("/funding/subsidy-postings/summary")
    public BenefitFundingService.SubsidyPostingSummary subsidyPostingSummary(@RequestParam Instant from,
            @RequestParam Instant to, @RequestParam(required = false) String campaignId,
            @RequestParam(defaultValue = "false") boolean includeBreakdown) {
        return service.subsidyPostingSummary(from, to, campaignId, includeBreakdown);
    }
}
