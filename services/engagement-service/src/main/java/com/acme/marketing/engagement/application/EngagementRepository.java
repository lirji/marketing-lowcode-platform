package com.acme.marketing.engagement.application;

import com.acme.marketing.engagement.application.EngagementService.ConsentRequest;
import com.acme.marketing.engagement.application.EngagementService.ConsentView;
import com.acme.marketing.engagement.application.EngagementService.ContactView;
import com.acme.marketing.engagement.application.EngagementService.FrequencyPolicy;
import com.acme.marketing.engagement.application.EngagementService.SendRequest;
import com.acme.marketing.engagement.application.EngagementService.SuppressionRequest;
import com.acme.marketing.engagement.application.EngagementService.TemplateRequest;
import com.acme.marketing.engagement.application.EngagementService.TemplateView;
import com.acme.marketing.provider.ProviderCallback;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 互动领域用例的持久化端口。 */
public interface EngagementRepository {

    /** 加锁读取当前同意版本。 */
    Optional<Long> lockConsentVersion(String tenantId, String subjectToken, String channel);

    /** 新增同意记录。 */
    void insertConsent(String tenantId, ConsentRequest request, long version, Instant updatedAt);

    /** 更新同意记录。 */
    void updateConsent(String tenantId, ConsentRequest request, long version, Instant updatedAt);

    /** 更新抑制记录，记录不存在时返回 false。 */
    boolean updateSuppression(String tenantId, SuppressionRequest request, Instant updatedAt);

    /** 新增抑制记录。 */
    void insertSuppression(String tenantId, SuppressionRequest request, Instant updatedAt);

    /** 更新频控策略，策略不存在时返回 false。 */
    boolean updateFrequencyPolicy(String tenantId, FrequencyPolicy policy, Instant updatedAt);

    /** 新增频控策略。 */
    void insertFrequencyPolicy(String tenantId, FrequencyPolicy policy, Instant updatedAt);

    /** 新增模板版本。 */
    void insertTemplate(String tenantId, String actorId, TemplateRequest request, Instant createdAt);

    /** 查询各模板的最新版本。 */
    List<TemplateView> findLatestTemplates(String tenantId);

    /** 按可选状态分页查询触达记录。 */
    List<ContactView> findContacts(String tenantId, String state, int limit);

    /** 加锁读取旅程副作用命令。 */
    Optional<CommandRecord> lockCommand(String tenantId, String commandId);

    /** 新增旅程副作用命令。 */
    void insertCommand(CommandWrite command);

    /** 标记旅程副作用命令成功。 */
    void markCommandSucceeded(String tenantId, String commandId, String contactId,
            String providerCode, Instant updatedAt);

    /** 标记旅程副作用命令待重试。 */
    void markCommandRetry(String tenantId, String commandId, String lastError, Instant updatedAt);

    /** 新增触达尝试。 */
    void insertContact(String tenantId, String contactId, SendRequest request,
            String variablesJson, Instant updatedAt);

    /** 回写服务商发送结果。 */
    void completeContact(String tenantId, String contactId, String state, String providerRequestId,
            String providerCode, Instant updatedAt);

    /** 新增永久失败触达的 DLQ 记录。 */
    void insertContactDlq(String tenantId, String dlqId, String contactId,
            String reasonCode, Instant createdAt);

    /** 判断服务商回执是否已经处理。 */
    boolean providerReceiptExists(String tenantId, String providerEventId);

    /** 保存服务商回执。 */
    void insertProviderReceipt(String tenantId, ProviderCallback callback, String attributesJson);

    /** 仅推进触达状态，用于处理异步回执。 */
    void updateContactState(String tenantId, String contactId, String state, Instant updatedAt);

    /** 查询当前同意投影。 */
    Optional<ConsentView> findConsent(String tenantId, String subjectToken, String channel);

    /** 判断当前主体是否处于有效抑制期。 */
    boolean isSuppressed(String tenantId, String subjectToken, String channel, Instant now);

    /** 查询频控策略。 */
    Optional<FrequencyPolicy> findFrequencyPolicy(String tenantId, String campaignId, String channel);

    /** 加锁读取当前频控桶计数。 */
    Optional<Integer> lockFrequencyCount(String tenantId, String campaignId, String channel,
            String subjectToken, long windowBucket);

    /** 新增频控桶。 */
    void insertFrequencyBucket(String tenantId, String campaignId, String channel,
            String subjectToken, long windowBucket, Instant updatedAt);

    /** 递增频控桶。 */
    void incrementFrequencyBucket(String tenantId, String campaignId, String channel,
            String subjectToken, long windowBucket, Instant updatedAt);

    /** 查询指定模板版本。 */
    Optional<TemplateView> findTemplate(String tenantId, String templateId, long version);

    /** 按业务幂等键查询触达记录。 */
    Optional<ContactView> findContactByKey(String tenantId, String contactKey);

    /** 按内部标识查询触达记录。 */
    Optional<ContactView> findContactById(String tenantId, String contactId);

    /** 按服务商请求号加锁查询触达记录。 */
    Optional<ContactView> lockContactByProviderRequest(String tenantId, String providerRequestId);

    /** 递增触达事件流序号；游标不存在时返回 false。 */
    boolean incrementOutboxSequence(String tenantId, String contactId, Instant updatedAt);

    /** 尝试创建触达事件流游标；并发创建成功时返回 false。 */
    boolean insertInitialOutboxSequence(String tenantId, String contactId, Instant updatedAt);

    /** 查询触达事件流序号。 */
    Optional<Long> findOutboxSequence(String tenantId, String contactId);

    /** 新增互动 outbox 事件。 */
    void insertOutbox(OutboxWrite outbox);

    /** 已存储的旅程副作用命令。 */
    record CommandRecord(String effectType, String enrollmentId, String payloadHash, String state,
            String contactId, String providerCode) { }

    /** 旅程副作用命令写模型。 */
    record CommandWrite(String tenantId, String commandId, String effectType, String enrollmentId,
            String payloadHash, String payloadJson, Instant createdAt) { }

    /** 互动 outbox 写模型。 */
    record OutboxWrite(String tenantId, String eventId, String contactId, String eventType,
            String destinationTopic, String partitionKey, long streamSequence, String payloadJson,
            Instant createdAt) { }
}
