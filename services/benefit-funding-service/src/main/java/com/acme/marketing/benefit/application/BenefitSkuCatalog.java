package com.acme.marketing.benefit.application;

import java.time.Instant;
import java.util.List;

/**
 * 权益中台 SKU 模板目录端口。营销域只保存 SKU 引用，不复制或编辑资产模板。
 */
public interface BenefitSkuCatalog {
    /** 按租户和模板状态查询可供营销绑定的 SKU。 */
    List<BenefitSkuView> list(String tenantId, SkuStatus status);

    /**
     * 实时确认 SKU 仍处于可投放状态。该方法不得复用列表缓存，因为发布门禁必须以最新状态为准。
     */
    default void requireActive(String tenantId, String skuId) {
        requireActiveSku(tenantId, skuId);
    }

    /**
     * 绕过列表缓存并返回 ACTIVE 模板快照。Assembler 需要同时以权威模板确定权益类型。
     */
    BenefitSkuView requireActiveSku(String tenantId, String skuId);

    enum SkuStatus { DRAFT, PENDING_APPROVAL, ACTIVE, PAUSED, RETIRED }

    /** 营销侧只读的权益模板视图，与权益中台 SkuView 契约对齐。 */
    record BenefitSkuView(
            String skuId,
            String benefitType,
            Long faceValueMinor,
            String currency,
            SkuStatus status,
            boolean enabled,
            String validityType,
            Instant validFrom,
            Instant validTo,
            Integer relativeDays,
            List<Integer> usableWeekdays,
            Long dailyQuota,
            Long userLimitPerDay,
            Long userLimitTotal,
            String equivalentSkuId,
            long version) {
        public BenefitSkuView {
            usableWeekdays = List.copyOf(usableWeekdays == null ? List.of() : usableWeekdays);
        }
    }
}
