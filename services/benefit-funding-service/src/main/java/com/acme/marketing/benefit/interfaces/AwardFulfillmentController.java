package com.acme.marketing.benefit.interfaces;

import com.acme.marketing.benefit.application.AwardFulfillmentService;
import com.acme.marketing.platform.isolation.TenantBulkhead;
import com.acme.marketing.platform.web.TenantContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 只读取发奖结果或请求重新查询，不能创建、补发或撤销权益。 */
@RestController
public final class AwardFulfillmentController {
    private final AwardFulfillmentService service;
    private final TenantBulkhead bulkhead;

    /** 沿用租户并发隔离和统一服务层授权。 */
    public AwardFulfillmentController(AwardFulfillmentService service, TenantBulkhead bulkhead) {
        this.service = service;
        this.bulkhead = bulkhead;
    }

    /** 返回带观察时间的最近结果，未观察到时不能以投递成功代替。 */
    @GetMapping("/api/v1/award-intents/{intentId}/fulfillment")
    public AwardFulfillmentService.View view(@PathVariable String intentId) {
        return bulkhead.execute(TenantContextHolder.requireCurrent().tenantId(), () -> service.view(intentId));
    }

    /** 相同意图的刷新合并到一条耐久任务，202 只代表请求刷新已受理。 */
    @PostMapping("/api/v1/award-intents/{intentId}/fulfillment:refresh")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AwardFulfillmentService.View refresh(@PathVariable String intentId) {
        return bulkhead.execute(TenantContextHolder.requireCurrent().tenantId(), () -> service.refresh(intentId));
    }
}
