package com.acme.marketing.lowcode.model;

/** 低代码方言标识；新增值需要编译器显式处理，旧方言语义保持不变。 */
public enum Dialect {
    OFFER_DECISION_DAG,
    AUDIENCE_EXPRESSION,
    JOURNEY_STATE_MACHINE,
    DMN_DECISION_TABLE,
    BENEFIT_POLICY,
    REFERRAL_POLICY
}
