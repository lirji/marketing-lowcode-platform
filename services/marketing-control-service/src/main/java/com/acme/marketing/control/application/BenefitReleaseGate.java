package com.acme.marketing.control.application;

import com.acme.marketing.platform.identity.TenantScope;
import java.util.Set;

/** 发布前向权益资金服务核验不可变 BenefitDefinition 版本仍绑定 ACTIVE SKU。 */
public interface BenefitReleaseGate {
    /** 业务发布授权和机器对权益服务的读取授权分别校验。 */
    default void assertBundleReleasable(TenantScope scope, Set<String> references) {
        assertReleasable(scope, references);
    }

    void assertReleasable(TenantScope scope, Set<String> benefitDefinitionVersions);
}
