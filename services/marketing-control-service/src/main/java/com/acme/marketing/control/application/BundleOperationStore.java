package com.acme.marketing.control.application;

/** 活动行锁负责分配序号，操作事实提供永久幂等、历史恢复及投影查询。 */
public interface BundleOperationStore {
    Row operation(Id id);
    Row byKey(Id id);
    Row latest(Position position);
    Row applied(Position position);
    boolean previouslyApplied(Id bundle);
    boolean killed(Id namespace);
    void insert(Row row);
    int sign(Sign write);
    int update(Change write);

    record Id(String tenantId, String value) {}
    record Position(String tenantId, String campaignId, String environment, String cell) {}
    record Row(String tenantId, String operationId, String campaignId, String environment, String cell,
            String bundleId, long version, String action, long expectedSequence, long activationSequence,
            String commandKey, String requestedDeadline, String applyBefore, String createdBy,
            String state, String reasonCode, String directiveJson, String createdAt, String updatedAt, String businessNo) {}
    record Sign(String tenantId, String operationId, String directiveJson, String updatedAt) {}
    record Change(String tenantId, String operationId, String state, String reasonCode, String updatedAt) {}
}
