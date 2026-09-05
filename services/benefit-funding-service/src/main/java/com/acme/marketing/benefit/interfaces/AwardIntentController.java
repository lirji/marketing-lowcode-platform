package com.acme.marketing.benefit.interfaces;

import com.acme.marketing.benefit.application.AwardIntentAssembler.AssembleCommand;
import com.acme.marketing.benefit.application.AwardIntentService;
import com.acme.marketing.benefit.application.AwardIntentService.AwardIntentView;
import com.acme.marketing.platform.isolation.TenantBulkhead;
import com.acme.marketing.platform.web.TenantContextHolder;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 发放意图的服务端触发入口与运营只读查询入口。 */
@RestController
public final class AwardIntentController {
    private final AwardIntentService service;
    private final TenantBulkhead bulkhead;

    /** 构造受单租户并发隔离保护的 HTTP 适配器。 */
    public AwardIntentController(AwardIntentService service, TenantBulkhead bulkhead) {
        this.service = service;
        this.bulkhead = bulkhead;
    }

    /**
     * 内部业务触发入口。不会经过 console 路由，金额仅从服务端签名 OfferToken 计算。
     */
    @PostMapping("/internal/v1/award-intents")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AwardIntentView create(@RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody AssembleCommand command) {
        return bulkhead.execute(TenantContextHolder.requireCurrent().tenantId(),
                () -> service.create(idempotencyKey, command));
    }

    /** 按营销活动返回租户隔离的发放状态，不返回主体原文或出站 payload。 */
    @GetMapping("/api/v1/award-intents")
    public List<AwardIntentView> list(@RequestParam String campaignId,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        return bulkhead.execute(TenantContextHolder.requireCurrent().tenantId(),
                () -> service.list(campaignId, limit, cursor));
    }
}
