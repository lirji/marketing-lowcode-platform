package com.acme.marketing.journeyservice.application;

import java.util.List;
import java.util.Optional;

/** Journey拥有的探针及子任务证据；完整作用域与唯一下标由数据库约束。 */
public interface JourneyExecutionProbeRepository {
    /** 只在不存在时创建，重复请求不能覆盖挑战或部署摘要。 */
    void ensure(Key key, StoredProbe value);
    /** 所有同挑战的登记/收集/一致查询先锁定父行。 */
    Optional<StoredProbe> lock(Key key);
    /** 最多128个不同子任务，调用方已持有父行锁。 */
    List<StoredAck> acknowledgements(Key key);
    /** 保存第一份已验证子任务证据，不按消息条数计覆盖率。 */
    void saveAck(Key key, StoredAck value);
    record Key(String tenantId, String environment, String cell, String namespace, String campaignId, String probeId) { }
    record StoredProbe(long activationSequence, String probeJson, String deploymentFingerprint, String expiresAt,
            String createdBy, String createdAt) { }
    record StoredAck(int subtaskIndex, String ackJson, String acceptedAt) { }
}
