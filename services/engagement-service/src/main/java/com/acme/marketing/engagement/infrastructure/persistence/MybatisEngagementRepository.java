package com.acme.marketing.engagement.infrastructure.persistence;

import static com.acme.marketing.platform.time.SqlTime.format;

import com.acme.marketing.engagement.application.EngagementRepository;
import com.acme.marketing.engagement.application.EngagementService.ConsentRequest;
import com.acme.marketing.engagement.application.EngagementService.ConsentView;
import com.acme.marketing.engagement.application.EngagementService.ContactState;
import com.acme.marketing.engagement.application.EngagementService.ContactView;
import com.acme.marketing.engagement.application.EngagementService.FrequencyPolicy;
import com.acme.marketing.engagement.application.EngagementService.SendRequest;
import com.acme.marketing.engagement.application.EngagementService.SuppressionRequest;
import com.acme.marketing.engagement.application.EngagementService.TemplateRequest;
import com.acme.marketing.engagement.application.EngagementService.TemplateView;
import com.acme.marketing.engagement.infrastructure.persistence.mapper.EngagementMapper;
import com.acme.marketing.provider.ProviderCallback;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

/** 基于 MyBatis 的互动领域持久化适配器。 */
@Repository
public class MybatisEngagementRepository implements EngagementRepository {
    private final EngagementMapper mapper;

    public MybatisEngagementRepository(EngagementMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<Long> lockConsentVersion(String tenantId, String subjectToken, String channel) {
        return Optional.ofNullable(mapper.selectConsentVersionForUpdate(tenantId, subjectToken, channel));
    }

    @Override
    public void insertConsent(String tenantId, ConsentRequest request, long version, Instant updatedAt) {
        requireOne(mapper.insertConsent(consent(tenantId, request, version, updatedAt)), "新增同意记录");
    }

    @Override
    public void updateConsent(String tenantId, ConsentRequest request, long version, Instant updatedAt) {
        requireOne(mapper.updateConsent(consent(tenantId, request, version, updatedAt)), "更新同意记录");
    }

    @Override
    public boolean updateSuppression(String tenantId, SuppressionRequest request, Instant updatedAt) {
        return mapper.updateSuppression(suppression(tenantId, request, updatedAt)) == 1;
    }

    @Override
    public void insertSuppression(String tenantId, SuppressionRequest request, Instant updatedAt) {
        requireOne(mapper.insertSuppression(suppression(tenantId, request, updatedAt)), "新增抑制记录");
    }

    @Override
    public boolean updateFrequencyPolicy(String tenantId, FrequencyPolicy policy, Instant updatedAt) {
        return mapper.updateFrequencyPolicy(frequencyPolicy(tenantId, policy, updatedAt)) == 1;
    }

    @Override
    public void insertFrequencyPolicy(String tenantId, FrequencyPolicy policy, Instant updatedAt) {
        requireOne(mapper.insertFrequencyPolicy(frequencyPolicy(tenantId, policy, updatedAt)), "新增频控策略");
    }

    @Override
    public void insertTemplate(String tenantId, String actorId, TemplateRequest request, Instant createdAt) {
        requireOne(mapper.insertTemplate(new EngagementMapper.TemplateWrite(tenantId, request.templateId(),
                request.version(), request.channel(), request.content(), String.join(",", request.requiredVariables()),
                "APPROVED", actorId, format(createdAt))), "新增模板版本");
    }

    @Override
    public List<TemplateView> findLatestTemplates(String tenantId) {
        return mapper.selectLatestTemplates(tenantId).stream().map(MybatisEngagementRepository::template).toList();
    }

    @Override
    public List<ContactView> findContacts(String tenantId, String state, int limit) {
        return mapper.selectContacts(tenantId, state, limit).stream()
                .map(MybatisEngagementRepository::contact).toList();
    }

    @Override
    public Optional<CommandRecord> lockCommand(String tenantId, String commandId) {
        EngagementMapper.CommandRow row = mapper.selectCommandForUpdate(tenantId, commandId);
        return row == null ? Optional.empty() : Optional.of(new CommandRecord(row.effectType(), row.enrollmentId(),
                row.payloadHash(), row.stateName(), value(row.contactId()), value(row.providerCode())));
    }

    @Override
    public void insertCommand(CommandWrite command) {
        String createdAt = format(command.createdAt());
        requireOne(mapper.insertCommand(new EngagementMapper.CommandWrite(command.tenantId(), command.commandId(),
                command.effectType(), command.enrollmentId(), command.payloadHash(), command.payloadJson(),
                "PENDING", createdAt, createdAt)), "新增旅程副作用命令");
    }

    @Override
    public void markCommandSucceeded(String tenantId, String commandId, String contactId,
            String providerCode, Instant updatedAt) {
        requireOne(mapper.markCommandSucceeded(new EngagementMapper.CommandSuccessWrite(tenantId, commandId,
                contactId, providerCode, format(updatedAt))), "完成旅程副作用命令");
    }

    @Override
    public void markCommandRetry(String tenantId, String commandId, String lastError, Instant updatedAt) {
        requireOne(mapper.markCommandRetry(new EngagementMapper.CommandRetryWrite(tenantId, commandId,
                lastError, format(updatedAt))), "更新旅程副作用命令重试状态");
    }

    @Override
    public void insertContact(String tenantId, String contactId, SendRequest request,
            String variablesJson, Instant updatedAt) {
        requireOne(mapper.insertContact(new EngagementMapper.ContactWrite(tenantId, contactId,
                request.contactKey(), request.subjectToken(), request.campaignId(), request.channel(),
                request.templateId(), request.templateVersion(), ContactState.PENDING.name(), variablesJson,
                format(request.requestedAt()), format(updatedAt))), "新增触达尝试");
    }

    @Override
    public void completeContact(String tenantId, String contactId, String state, String providerRequestId,
            String providerCode, Instant updatedAt) {
        requireOne(mapper.completeContact(new EngagementMapper.ContactCompleteWrite(tenantId, contactId, state,
                providerRequestId, providerCode, format(updatedAt))), "回写服务商发送结果");
    }

    @Override
    public void insertContactDlq(String tenantId, String dlqId, String contactId,
            String reasonCode, Instant createdAt) {
        requireOne(mapper.insertContactDlq(new EngagementMapper.ContactDlqWrite(tenantId, dlqId, contactId,
                reasonCode, "OPEN", format(createdAt))), "新增触达死信");
    }

    @Override
    public boolean providerReceiptExists(String tenantId, String providerEventId) {
        return mapper.countProviderReceipt(tenantId, providerEventId) > 0;
    }

    @Override
    public void insertProviderReceipt(String tenantId, ProviderCallback callback, String attributesJson) {
        requireOne(mapper.insertProviderReceipt(new EngagementMapper.ProviderReceiptWrite(tenantId,
                callback.providerEventId(), callback.providerRequestId(), callback.status().name(),
                format(callback.occurredAt()), attributesJson)), "保存服务商回执");
    }

    @Override
    public void updateContactState(String tenantId, String contactId, String state, Instant updatedAt) {
        requireOne(mapper.updateContactState(new EngagementMapper.ContactStateWrite(tenantId, contactId, state,
                format(updatedAt))), "推进触达状态");
    }

    @Override
    public Optional<ConsentView> findConsent(String tenantId, String subjectToken, String channel) {
        EngagementMapper.ConsentRow row = mapper.selectConsent(tenantId, subjectToken, channel);
        return row == null ? Optional.empty() : Optional.of(new ConsentView(subjectToken, channel,
                row.allowedValue(), row.minorValue(), row.personalizationAllowed(), row.versionNo(),
                Instant.parse(row.effectiveAt())));
    }

    @Override
    public boolean isSuppressed(String tenantId, String subjectToken, String channel, Instant now) {
        return mapper.countActiveSuppression(tenantId, subjectToken, channel, format(now)) > 0;
    }

    @Override
    public Optional<FrequencyPolicy> findFrequencyPolicy(String tenantId, String campaignId, String channel) {
        EngagementMapper.FrequencyPolicyRow row = mapper.selectFrequencyPolicy(tenantId, campaignId, channel);
        return row == null ? Optional.empty() : Optional.of(new FrequencyPolicy(campaignId, channel,
                row.windowSeconds(), row.maxContacts(), LocalTime.parse(row.quietStart()),
                LocalTime.parse(row.quietEnd())));
    }

    @Override
    public Optional<Integer> lockFrequencyCount(String tenantId, String campaignId, String channel,
            String subjectToken, long windowBucket) {
        return Optional.ofNullable(mapper.selectFrequencyCountForUpdate(tenantId, campaignId, channel,
                subjectToken, windowBucket));
    }

    @Override
    public void insertFrequencyBucket(String tenantId, String campaignId, String channel,
            String subjectToken, long windowBucket, Instant updatedAt) {
        requireOne(mapper.insertFrequencyBucket(bucket(tenantId, campaignId, channel, subjectToken,
                windowBucket, updatedAt)), "新增频控桶");
    }

    @Override
    public void incrementFrequencyBucket(String tenantId, String campaignId, String channel,
            String subjectToken, long windowBucket, Instant updatedAt) {
        requireOne(mapper.incrementFrequencyBucket(bucket(tenantId, campaignId, channel, subjectToken,
                windowBucket, updatedAt)), "递增频控桶");
    }

    @Override
    public Optional<TemplateView> findTemplate(String tenantId, String templateId, long version) {
        EngagementMapper.TemplateRow row = mapper.selectTemplate(tenantId, templateId, version);
        return row == null ? Optional.empty() : Optional.of(template(row));
    }

    @Override
    public Optional<ContactView> findContactByKey(String tenantId, String contactKey) {
        return optionalContact(mapper.selectContactByKey(tenantId, contactKey));
    }

    @Override
    public Optional<ContactView> findContactById(String tenantId, String contactId) {
        return optionalContact(mapper.selectContactById(tenantId, contactId));
    }

    @Override
    public Optional<ContactView> lockContactByProviderRequest(String tenantId, String providerRequestId) {
        return optionalContact(mapper.selectContactByProviderRequestForUpdate(tenantId, providerRequestId));
    }

    @Override
    public boolean incrementOutboxSequence(String tenantId, String contactId, Instant updatedAt) {
        return mapper.incrementOutboxSequence(position(tenantId, contactId, 0, updatedAt)) == 1;
    }

    @Override
    public boolean insertInitialOutboxSequence(String tenantId, String contactId, Instant updatedAt) {
        try {
            requireOne(mapper.insertOutboxSequence(position(tenantId, contactId, 1, updatedAt)),
                    "创建互动 outbox 游标");
            return true;
        } catch (DuplicateKeyException race) {
            return false;
        }
    }

    @Override
    public Optional<Long> findOutboxSequence(String tenantId, String contactId) {
        return Optional.ofNullable(mapper.selectOutboxSequence(tenantId, contactId));
    }

    @Override
    public void insertOutbox(OutboxWrite outbox) {
        String createdAt = format(outbox.createdAt());
        requireOne(mapper.insertOutbox(new EngagementMapper.OutboxWrite(outbox.tenantId(), outbox.eventId(),
                outbox.contactId(), outbox.eventType(), outbox.destinationTopic(), outbox.partitionKey(),
                outbox.streamSequence(), outbox.payloadJson(), createdAt, createdAt)), "新增互动 outbox 事件");
    }

    private static EngagementMapper.ConsentWrite consent(String tenantId, ConsentRequest request,
            long version, Instant updatedAt) {
        return new EngagementMapper.ConsentWrite(tenantId, request.subjectToken(), request.channel(),
                request.allowed(), request.minor(), request.personalizationAllowed(), version, request.source(),
                format(request.effectiveAt()), format(updatedAt));
    }

    private static EngagementMapper.SuppressionWrite suppression(String tenantId, SuppressionRequest request,
            Instant updatedAt) {
        return new EngagementMapper.SuppressionWrite(tenantId, request.subjectToken(), request.channel(),
                request.reason(), request.expiresAt() == null ? null : format(request.expiresAt()),
                format(updatedAt));
    }

    private static EngagementMapper.FrequencyPolicyWrite frequencyPolicy(String tenantId, FrequencyPolicy policy,
            Instant updatedAt) {
        return new EngagementMapper.FrequencyPolicyWrite(tenantId, policy.campaignId(), policy.channel(),
                policy.windowSeconds(), policy.maxContacts(), policy.quietStart().toString(),
                policy.quietEnd().toString(), format(updatedAt));
    }

    private static EngagementMapper.FrequencyBucketWrite bucket(String tenantId, String campaignId,
            String channel, String subjectToken, long windowBucket, Instant updatedAt) {
        return new EngagementMapper.FrequencyBucketWrite(tenantId, campaignId, channel, subjectToken,
                windowBucket, format(updatedAt));
    }

    private static EngagementMapper.OutboxPositionWrite position(String tenantId, String contactId,
            long sequence, Instant updatedAt) {
        return new EngagementMapper.OutboxPositionWrite(tenantId, contactId, sequence, format(updatedAt));
    }

    private static TemplateView template(EngagementMapper.TemplateRow row) {
        return new TemplateView(row.templateId(), row.versionNo(), row.channelName(), row.contentText(),
                csv(row.requiredVariables()), row.stateName(), Instant.parse(row.createdAt()));
    }

    private static Optional<ContactView> optionalContact(EngagementMapper.ContactRow row) {
        return row == null ? Optional.empty() : Optional.of(contact(row));
    }

    private static ContactView contact(EngagementMapper.ContactRow row) {
        return new ContactView(row.contactId(), row.contactKey(), ContactState.valueOf(row.stateName()),
                value(row.providerRequestId()), value(row.providerCode()), Instant.parse(row.updatedAt()));
    }

    private static Set<String> csv(String encoded) {
        return encoded == null || encoded.isBlank() ? Set.of() : Set.copyOf(Arrays.asList(encoded.split(",")));
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    private static void requireOne(int affected, String operation) {
        if (affected != 1) throw new IllegalStateException(operation + "的受影响行数不正确");
    }
}
