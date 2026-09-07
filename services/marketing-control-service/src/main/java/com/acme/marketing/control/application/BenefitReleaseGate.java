package com.acme.marketing.control.application;

import com.acme.marketing.platform.identity.TenantScope;
import java.util.Set;

/** 发布前向权益资金服务核验不可变 BenefitDefinition 版本仍绑定 ACTIVE SKU。 */
public interface BenefitReleaseGate {
    void assertReleasable(TenantScope scope, Set<String> benefitDefinitionVersions);
}
