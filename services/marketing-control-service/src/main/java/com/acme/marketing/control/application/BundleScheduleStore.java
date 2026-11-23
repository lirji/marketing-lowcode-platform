package com.acme.marketing.control.application;

import java.util.List;
import java.util.Set;

/** 定时发布持久边界。租约到期后用同一个业务命令编号接管。 */
public interface BundleScheduleStore {
    record Row(String tenant, String campaignId, String scheduleId, String commandKey, long version,
               long expectedAppliedSequence, String dueAt, String applyBefore, String state, long revision,
               String nextAttemptAt, String operationId, String reasonCode, String createdBy,
               String createdAt, String updatedAt) { }
    record Change(String tenant, String campaignId, String scheduleId, long revision, String state,
                  String nextAttemptAt, String operationId, String reasonCode, String updatedAt) { }

    Row byCommand(String tenant, String campaignId, String commandKey);
    Row byId(String tenant, String campaignId, String scheduleId);
    List<Row> recent(String tenant, String campaignId, long version);
    Row claimable(String tenant, Set<String> organizations, Set<String> shops, String now);
    int insert(Row row);
    int advance(Change change);
}
