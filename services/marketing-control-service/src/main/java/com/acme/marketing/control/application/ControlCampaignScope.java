package com.acme.marketing.control.application;

import com.acme.marketing.platform.error.NotFoundException;
import com.acme.marketing.platform.identity.TenantScope;

/** 活动及其发布资源共用同一归属检查，防止新入口遗漏组织或店铺范围。 */
final class ControlCampaignScope {
    private ControlCampaignScope() { }

    /** 写入方可锁定活动行；错误与数据范围沿用既有活动契约。 */
    static void require(ControlRepository repository, TenantScope scope, String campaignId, boolean lock) {
        var ownership = repository.findCampaignOwnership(scope.tenantId().value(), campaignId, lock)
                .orElseThrow(() -> new NotFoundException("CAMPAIGN_NOT_FOUND", "campaign not found"));
        scope.requireOrganization(ownership.organizationId());
        if (ownership.shopId() != null && !ownership.shopId().isBlank()) scope.requireShop(ownership.shopId());
    }
}
