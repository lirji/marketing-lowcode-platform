package com.acme.marketing.benefit.application;

import java.util.List;

/** 发奖 Relay 的租约与投递结果持久化端口。 */
public interface AwardIntentRelayRepository {
    List<PendingIntent> findCandidates(String now, int tenantLimit, int totalLimit);
    int claim(ClaimWrite write);
    int markSent(SentWrite write);
    int markFailure(FailureWrite write);

    record PendingIntent(String tenantId, String intentId, String sourceRequestId, String payload,
            int attempts, long leaseVersion) { }
    record ClaimWrite(String tenantId, String intentId, String workerId, long expectedLeaseVersion,
            String leaseUntil, String now) { }
    record SentWrite(String tenantId, String intentId, String workerId, long leaseVersion,
            String orderNo, String now) { }
    record FailureWrite(String tenantId, String intentId, String workerId, long leaseVersion,
            String statusName, String nextAttemptAt, String lastError, String now) { }
}
