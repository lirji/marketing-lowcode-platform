package com.acme.marketing.engagement.infrastructure.persistence.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 互动服务 MyBatis Mapper。
 *
 * <p>接口只声明数据库操作，SQL 统一维护在 Mapper XML 中。
 */
@Mapper
public interface EngagementMapper {

    /** 加锁查询同意版本。 */
    Long selectConsentVersionForUpdate(@Param("tenantId") String tenantId,
            @Param("subjectToken") String subjectToken, @Param("channelName") String channelName);

    /** 新增同意记录。 */
    int insertConsent(ConsentWrite consent);

    /** 更新同意记录。 */
    int updateConsent(ConsentWrite consent);

    /** 更新抑制记录。 */
    int updateSuppression(SuppressionWrite suppression);

    /** 新增抑制记录。 */
    int insertSuppression(SuppressionWrite suppression);

    /** 更新频控策略。 */
    int updateFrequencyPolicy(FrequencyPolicyWrite policy);

    /** 新增频控策略。 */
    int insertFrequencyPolicy(FrequencyPolicyWrite policy);

    /** 新增模板版本。 */
    int insertTemplate(TemplateWrite template);

    /** 查询各模板最新版本。 */
    List<TemplateRow> selectLatestTemplates(@Param("tenantId") String tenantId);

    /** 按可选状态查询触达记录。 */
    List<ContactRow> selectContacts(@Param("tenantId") String tenantId,
            @Param("stateName") String stateName, @Param("limit") int limit);

    /** 加锁查询旅程副作用命令。 */
    CommandRow selectCommandForUpdate(@Param("tenantId") String tenantId,
            @Param("commandId") String commandId);

    /** 新增旅程副作用命令。 */
    int insertCommand(CommandWrite command);

    /** 标记旅程副作用命令成功。 */
    int markCommandSucceeded(CommandSuccessWrite command);

    /** 标记旅程副作用命令待重试。 */
    int markCommandRetry(CommandRetryWrite command);

    /** 新增触达尝试。 */
    int insertContact(ContactWrite contact);

    /** 回写服务商发送结果。 */
    int completeContact(ContactCompleteWrite contact);

    /** 新增触达 DLQ。 */
    int insertContactDlq(ContactDlqWrite dlq);

    /** 查询服务商回执数量。 */
    int countProviderReceipt(@Param("tenantId") String tenantId,
            @Param("providerEventId") String providerEventId);

    /** 新增服务商回执。 */
    int insertProviderReceipt(ProviderReceiptWrite receipt);

    /** 推进触达状态。 */
    int updateContactState(ContactStateWrite contact);

    /** 查询同意投影。 */
    ConsentRow selectConsent(@Param("tenantId") String tenantId,
            @Param("subjectToken") String subjectToken, @Param("channelName") String channelName);

    /** 查询有效抑制记录数量。 */
    int countActiveSuppression(@Param("tenantId") String tenantId,
            @Param("subjectToken") String subjectToken, @Param("channelName") String channelName,
            @Param("now") String now);

    /** 查询频控策略。 */
    FrequencyPolicyRow selectFrequencyPolicy(@Param("tenantId") String tenantId,
            @Param("campaignId") String campaignId, @Param("channelName") String channelName);

    /** 加锁查询频控桶。 */
    Integer selectFrequencyCountForUpdate(@Param("tenantId") String tenantId,
            @Param("campaignId") String campaignId, @Param("channelName") String channelName,
            @Param("subjectToken") String subjectToken, @Param("windowBucket") long windowBucket);

    /** 新增频控桶。 */
    int insertFrequencyBucket(FrequencyBucketWrite bucket);

    /** 递增频控桶。 */
    int incrementFrequencyBucket(FrequencyBucketWrite bucket);

    /** 查询模板版本。 */
    TemplateRow selectTemplate(@Param("tenantId") String tenantId,
            @Param("templateId") String templateId, @Param("versionNo") long versionNo);

    /** 按业务幂等键查询触达记录。 */
    ContactRow selectContactByKey(@Param("tenantId") String tenantId,
            @Param("contactKey") String contactKey);

    /** 按内部标识查询触达记录。 */
    ContactRow selectContactById(@Param("tenantId") String tenantId,
            @Param("contactId") String contactId);

    /** 按服务商请求号加锁查询触达记录。 */
    ContactRow selectContactByProviderRequestForUpdate(@Param("tenantId") String tenantId,
            @Param("providerRequestId") String providerRequestId);

    /** 递增触达事件流序号。 */
    int incrementOutboxSequence(OutboxPositionWrite position);

    /** 创建触达事件流游标。 */
    int insertOutboxSequence(OutboxPositionWrite position);

    /** 查询触达事件流序号。 */
    Long selectOutboxSequence(@Param("tenantId") String tenantId, @Param("contactId") String contactId);

    /** 新增互动 outbox 事件。 */
    int insertOutbox(OutboxWrite outbox);

    /** 加锁查询待发布互动事件。 */
    List<OutboxRow> selectPendingOutbox(@Param("now") String now, @Param("limit") int limit);

    /** 标记 outbox 发布成功。 */
    int markOutboxPublished(OutboxStatusWrite status);

    /** 标记 outbox 永久失败。 */
    int markOutboxDead(OutboxStatusWrite status);

    /** 标记 outbox 待重试。 */
    int markOutboxRetry(OutboxStatusWrite status);

    record ConsentWrite(String tenantId, String subjectToken, String channelName, boolean allowedValue,
            boolean minorValue, boolean personalizationAllowed, long versionNo, String sourceName,
            String effectiveAt, String updatedAt) { }
    record SuppressionWrite(String tenantId, String subjectToken, String channelName, String reasonText,
            String expiresAt, String updatedAt) { }
    record FrequencyPolicyWrite(String tenantId, String campaignId, String channelName, long windowSeconds,
            int maxContacts, String quietStart, String quietEnd, String updatedAt) { }
    record TemplateWrite(String tenantId, String templateId, long versionNo, String channelName,
            String contentText, String requiredVariables, String stateName, String createdBy,
            String createdAt) { }
    record CommandWrite(String tenantId, String commandId, String effectType, String enrollmentId,
            String payloadHash, String payloadJson, String stateName, String createdAt, String updatedAt) { }
    record CommandSuccessWrite(String tenantId, String commandId, String contactId, String providerCode,
            String updatedAt) { }
    record CommandRetryWrite(String tenantId, String commandId, String lastError, String updatedAt) { }
    record ContactWrite(String tenantId, String contactId, String contactKey, String subjectToken,
            String campaignId, String channelName, String templateId, long templateVersion, String stateName,
            String variablesJson, String requestedAt, String updatedAt) { }
    record ContactCompleteWrite(String tenantId, String contactId, String stateName, String providerRequestId,
            String providerCode, String updatedAt) { }
    record ContactDlqWrite(String tenantId, String dlqId, String contactId, String reasonCode,
            String stateName, String createdAt) { }
    record ProviderReceiptWrite(String tenantId, String providerEventId, String providerRequestId,
            String statusName, String occurredAt, String attributesJson) { }
    record ContactStateWrite(String tenantId, String contactId, String stateName, String updatedAt) { }
    record FrequencyBucketWrite(String tenantId, String campaignId, String channelName, String subjectToken,
            long windowBucket, String updatedAt) { }
    record OutboxPositionWrite(String tenantId, String contactId, long lastSequence, String updatedAt) { }
    record OutboxWrite(String tenantId, String eventId, String contactId, String eventType,
            String destinationTopic, String partitionKey, long streamSequence, String payloadJson,
            String nextAttemptAt, String createdAt) { }
    record OutboxStatusWrite(String tenantId, String eventId, int publishAttempts, String stateAt,
            String lastError) { }

    record TemplateRow(String templateId, long versionNo, String channelName, String contentText,
            String requiredVariables, String stateName, String createdAt) { }
    record ContactRow(String contactId, String contactKey, String stateName, String providerRequestId,
            String providerCode, String updatedAt) { }
    record CommandRow(String effectType, String enrollmentId, String payloadHash, String stateName,
            String contactId, String providerCode) { }
    record ConsentRow(boolean allowedValue, boolean minorValue, boolean personalizationAllowed,
            long versionNo, String effectiveAt) { }
    record FrequencyPolicyRow(long windowSeconds, int maxContacts, String quietStart, String quietEnd) { }
    record OutboxRow(String tenantId, String eventId, String destinationTopic, String partitionKey,
            String payloadJson, int publishAttempts) { }
}
