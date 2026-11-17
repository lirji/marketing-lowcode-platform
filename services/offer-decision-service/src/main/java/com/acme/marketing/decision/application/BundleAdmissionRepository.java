package com.acme.marketing.decision.application;

import java.util.Optional;

/** 同cell各namespace共享的准入权威；不提供把接收记录提升为已应用的入口。 */
public interface BundleAdmissionRepository {
    /** 事务首个SQL用共享锁固定当前应用位置，允许不同参与并发、阻止切换穿越。 */
    Optional<AppliedAuthorization> lockApplied(Key key);
    /** 不可变已应用历史精确读取，用于老参与恢复。 */
    Optional<AppliedAuthorization> applied(Key key, long sequence);
    /** READ_COMMITTED下读取不可变参与事实，不锁不存在的间隙。 */
    Optional<StoredParticipation> participation(Key key, String participationId);
    /** 以数据库业务主键仲裁同键竞争；返回false时必须读取并核验获胜事实。 */
    boolean insert(Key key, String participationId, StoredParticipation value);

    record Key(String tenantId, String environment, String cell, String campaignId) { }
    record AppliedAuthorization(long activationSequence, String directiveId, String directiveJson, String appliedAt) { }
    record StoredParticipation(String bindingId, String subjectHash, long activationSequence,
            String participationJson, String createdBy, String createdAt) { }
}
