package com.acme.marketing.engagement.application;

import static com.acme.marketing.platform.time.SqlTime.format;

import com.acme.marketing.contracts.event.JourneyEffectCommand;
import com.acme.marketing.platform.crypto.Digests;
import com.acme.marketing.platform.error.ConflictException;
import com.acme.marketing.platform.error.NotFoundException;
import com.acme.marketing.platform.idempotency.IdempotencyConflictException;
import com.acme.marketing.platform.web.TenantContextHolder;
import com.acme.marketing.provider.ProviderCallback;
import com.acme.marketing.provider.ProviderConnector;
import com.acme.marketing.provider.ProviderPolicy;
import com.acme.marketing.provider.ProviderRequest;
import com.acme.marketing.provider.ProviderResult;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.SerializationFeature;

@Service
public final class EngagementService {
    private final EngagementRepository repository;
    private final ObjectMapper mapper;
    private final ObjectWriter requestWriter;
    private final Clock clock;
    private final ProviderConnector provider;
    private final ProviderRoute providerRoute;
    private final TransactionTemplate transactions;

    public EngagementService(EngagementRepository repository, ObjectMapper mapper, Clock clock,
            ProviderConnector provider, ProviderRoute providerRoute, TransactionTemplate transactions) {
        this.repository = repository;
        this.mapper = mapper;
        this.requestWriter = mapper.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        this.clock = clock;
        this.provider = provider;
        this.providerRoute = providerRoute;
        this.transactions = transactions;
    }

    public ConsentView setConsent(ConsentRequest request) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("consent:write");
        return transactions.execute(ignored -> {
            String tenantId = scope.tenantId().value();
            Long current = repository.lockConsentVersion(tenantId, request.subjectToken(), request.channel())
                    .orElse(null);
            long version = current == null ? 1 : current + 1;
            Instant now = clock.instant();
            if (current == null) {
                repository.insertConsent(tenantId, request, version, now);
            } else {
                repository.updateConsent(tenantId, request, version, now);
            }
            return new ConsentView(request.subjectToken(), request.channel(), request.allowed(), request.minor(),
                    request.personalizationAllowed(), version, request.effectiveAt());
        });
    }

    public SuppressionView suppress(SuppressionRequest request) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("suppression:write");
        return transactions.execute(ignored -> {
            String tenantId = scope.tenantId().value();
            Instant now = clock.instant();
            if (!repository.updateSuppression(tenantId, request, now)) {
                repository.insertSuppression(tenantId, request, now);
            }
            return new SuppressionView(request.subjectToken(), request.channel(), request.reason(), request.expiresAt());
        });
    }

    public FrequencyPolicy setPolicy(FrequencyPolicy policy) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("engagement-policy:write");
        transactions.executeWithoutResult(ignored -> {
            String tenantId = scope.tenantId().value();
            Instant now = clock.instant();
            if (!repository.updateFrequencyPolicy(tenantId, policy, now)) {
                repository.insertFrequencyPolicy(tenantId, policy, now);
            }
        });
        return policy;
    }

    /** 即使命令命中旧成功回执，也必须仍有当前写权限且输入合法。 */
    public void authorizeTemplateWrite(TemplateRequest request) {
        TenantContextHolder.requireCurrent().requirePermission("template:write");
        TemplateInput.validate(request);
    }

    /** 新版本只追加；冲突明确返回409，不覆盖已被旅程引用的历史正文。 */
    public TemplateView createTemplate(TemplateRequest request) {
        authorizeTemplateWrite(request);
        var scope = TenantContextHolder.requireCurrent();
        Instant now = clock.instant();
        try {
            transactions.executeWithoutResult(ignored -> repository.insertTemplate(scope.tenantId().value(),
                    scope.actorId(), request, now));
        } catch (DuplicateKeyException conflict) {
            throw TemplateInput.versionExists();
        }
        return new TemplateView(request.templateId(), request.version(), request.channel(), request.content(),
                request.requiredVariables(), TemplateState.APPROVED.code(), now);
    }

    public List<TemplateView> templates() {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("template:read");
        return repository.findLatestTemplates(scope.tenantId().value());
    }

    public ContactView send(SendRequest request) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("contact:send");
        return sendForTenant(scope.tenantId().value(), request);
    }

    public ContactView contact(String contactKey) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("contact:read");
        if (contactKey == null || contactKey.isBlank()) {
            throw new IllegalArgumentException("contact key is required");
        }
        return contactByKey(scope.tenantId().value(), contactKey)
                .orElseThrow(() -> new NotFoundException("CONTACT_NOT_FOUND", "contact not found"));
    }

    public List<ContactView> contacts(String state, int limit) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("contact:read");
        if (limit < 1 || limit > 200) throw new IllegalArgumentException("limit must be in [1,200]");
        String stateName = null;
        if (state != null && !state.isBlank()) {
            ContactState parsed;
            try {
                parsed = ContactState.valueOf(state.toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("contact state is invalid", invalid);
            }
            stateName = parsed.name();
        }
        return repository.findContacts(scope.tenantId().value(), stateName, limit);
    }

    public CommandResult handleJourneyCommand(JourneyEffectCommand command) {
        if (!Set.of("SEND", "WEBHOOK").contains(command.effectType())) {
            throw new IllegalArgumentException("engagement cannot execute effect type " + command.effectType());
        }
        String encoded = json(command);
        String hash = Digests.sha256Hex(encoded);
        CommandRow claimed = transactions.execute(ignored -> claimCommand(command, encoded, hash));
        if (claimed == null) throw new IllegalStateException("engagement command claim returned no result");
        if ("SUCCEEDED".equals(claimed.state())) {
            return new CommandResult(command.commandId(), claimed.state(), claimed.contactId(), claimed.providerCode());
        }
        try {
            CommandResult result = "SEND".equals(command.effectType())
                    ? executeJourneySend(command) : executeJourneyWebhook(command);
            transactions.executeWithoutResult(ignored -> repository.markCommandSucceeded(command.tenantId(),
                    command.commandId(), emptyToNull(result.contactId()), result.providerCode(), clock.instant()));
            return result;
        } catch (RuntimeException failure) {
            transactions.executeWithoutResult(ignored -> repository.markCommandRetry(command.tenantId(),
                    command.commandId(), safeMessage(failure), clock.instant()));
            throw failure;
        }
    }

    private ContactView sendForTenant(String tenantId, SendRequest request) {
        Pending pending;
        try {
            pending = transactions.execute(ignored -> prepare(tenantId, request));
        } catch (DuplicateKeyException collision) {
            // 首次并发请求由唯一键决定归属；原事务已回滚，新事务才能读到赢家提交的身份。
            pending = transactions.execute(ignored -> {
                ContactView winner = contactByKey(tenantId, request.contactKey()).orElse(null);
                if (winner == null) throw collision;
                verifyContactIdentity(tenantId, request, winner, requestFingerprint(request));
                // 赢家已取得发送资格，竞争失败者只读返回，不能再次调用渠道或占用频控。
                return new Pending(false, winner);
            });
        }
        if (pending == null) throw new IllegalStateException("contact transaction returned no result");
        if (!pending.created()) return pending.view();
        ProviderRequest providerRequest = new ProviderRequest(tenantId, request.contactKey(),
                request.channel(), request.recipientToken(), request.templateId() + ':' + request.templateVersion(),
                providerRoute.endpoint(tenantId, request.channel()), request.variables(), clock.instant());
        ProviderResult result = provider.send(providerRequest, ProviderPolicy.defaults());
        String contactId = pending.view().contactId();
        return transactions.execute(ignored -> complete(tenantId, contactId, result));
    }

    private Pending prepare(String tenantId, SendRequest request) {
        ContactView current = contactByKey(tenantId, request.contactKey()).orElse(null);
        String fingerprint = requestFingerprint(request);
        if (current != null) {
            verifyContactIdentity(tenantId, request, current, fingerprint);
            if (!retryable(current)) return new Pending(false, current);
        }
        Instant now = clock.instant();
        String contactId = current == null ? UUID.randomUUID().toString() : current.contactId();
        if (current == null) {
            // 先争取请求键再锁频控，防止并发异载荷先被误判为频控超限；后续许可失败会整体回滚。
            repository.insertContact(tenantId, contactId, request, json(request.variables()), fingerprint, now);
        }
        // 重试可能跨越撤回同意、抑制生效或静默时段，不能复用首次发送时的许可。
        ConsentView consent = consent(tenantId, request.subjectToken(), request.channel());
        if (!consent.allowed() || consent.effectiveAt().isAfter(now)) {
            throw new ConflictException("CONSENT_REQUIRED", "current channel consent is required");
        }
        if (consent.minor() && request.personalized()) {
            throw new ConflictException("MINOR_PERSONALIZATION_BLOCKED", "personalized contact to minor is blocked");
        }
        if (request.personalized() && !consent.personalizationAllowed()) {
            throw new ConflictException("PERSONALIZATION_OPT_OUT", "subject opted out of personalization");
        }
        if (suppressed(tenantId, request.subjectToken(), request.channel())) {
            throw new ConflictException("SUBJECT_SUPPRESSED", "subject is suppressed");
        }
        FrequencyPolicy policy = policy(tenantId, request.campaignId(), request.channel());
        if (inQuietHours(now, request.timezone(), policy.quietStart(), policy.quietEnd())) {
            throw new ConflictException("QUIET_HOURS", "contact is inside subject quiet hours");
        }
        TemplateView template = template(tenantId, request.templateId(), request.templateVersion());
        if (!TemplateState.APPROVED.code().equals(template.state()) || !template.channel().equals(request.channel())
                || !request.variables().keySet().containsAll(template.requiredVariables())) {
            throw new ConflictException("TEMPLATE_VARIABLES_INVALID", "required template variables are missing");
        }
        // 原触达已经占用频控名额；重试通过当前策略后复用该记录，不能再次扣减。
        if (current != null) return new Pending(true, current);
        consumeFrequency(tenantId, request, policy, now);
        outbox(tenantId, contactId, "ContactRequested", now);
        return new Pending(true, new ContactView(contactId, request.contactKey(), ContactState.PENDING, "", "", now));
    }

    /** 请求键绑定不可变身份，不能使用新请求补猜历史收件人。 */
    private void verifyContactIdentity(String tenantId, SendRequest request, ContactView current, String fingerprint) {
        String original = repository.findContactFingerprint(tenantId, request.contactKey()).orElse(null);
        if (original == null && retryable(current)) throw ContactDeliveryProblem.RETRY_UNVERIFIABLE.exception();
        if (original != null && !Digests.constantTimeEquals(original, fingerprint)) {
            throw new IdempotencyConflictException();
        }
    }

    private static boolean retryable(ContactView contact) {
        return contact.state() == ContactState.PENDING || contact.state() == ContactState.RETRY_PENDING;
    }

    private ContactView complete(String tenantId, String contactId, ProviderResult result) {
        ContactState state = switch (result.status()) {
            case ACCEPTED -> ContactState.ACCEPTED;
            case RETRY_EXHAUSTED, RATE_LIMITED, CIRCUIT_OPEN -> ContactState.RETRY_PENDING;
            case PERMANENT_FAILURE -> ContactState.FAILED;
        };
        Instant now = clock.instant();
        // 回执可能在重试HTTP尚未返回时先推进到终态；迟到结果不能回退状态或重复写出终态事件。
        if (!repository.completeContact(tenantId, contactId, state.name(), result.providerRequestId(), result.code(), now)) {
            return contactById(tenantId, contactId);
        }
        if (state == ContactState.FAILED) {
            repository.insertContactDlq(tenantId, UUID.randomUUID().toString(), contactId, result.code(), now);
        }
        outbox(tenantId, contactId, "Contact" + state.name(), now);
        return new ContactView(contactId, contactById(tenantId, contactId).contactKey(), state,
                result.providerRequestId(), result.code(), now);
    }

    private CommandResult executeJourneySend(JourneyEffectCommand command) {
        Map<String, String> payload = command.payload();
        String channel = required(payload, "channel");
        String templateId = payload.getOrDefault("templateId", payload.getOrDefault("template", ""));
        if (templateId.isBlank()) throw new IllegalArgumentException("journey SEND requires templateId");
        long templateVersion = positiveLong(payload.getOrDefault("templateVersion", "1"), "templateVersion");
        Map<String, Object> variables = new LinkedHashMap<>();
        payload.forEach(variables::put);
        SendRequest request = new SendRequest(command.commandId(), command.subjectToken(),
                payload.getOrDefault("campaignId", command.journeyId()), channel,
                payload.getOrDefault("recipientToken", command.subjectToken()), templateId, templateVersion,
                variables, payload.getOrDefault("timezone", "UTC"),
                Boolean.parseBoolean(payload.getOrDefault("personalized", "false")),
                Instant.ofEpochMilli(command.createdAtEpochMillis()), null);
        ContactView contact = sendForTenant(command.tenantId(), request);
        if (contact.state() == ContactState.PENDING || contact.state() == ContactState.RETRY_PENDING) {
            throw new IllegalStateException("provider delivery remains retryable: " + contact.providerCode());
        }
        if (contact.state() == ContactState.FAILED) {
            throw new ConflictException("PROVIDER_PERMANENT_FAILURE", contact.providerCode());
        }
        return new CommandResult(command.commandId(), "SUCCEEDED", contact.contactId(), contact.providerCode());
    }

    private CommandResult executeJourneyWebhook(JourneyEffectCommand command) {
        Map<String, Object> variables = new LinkedHashMap<>();
        command.payload().forEach(variables::put);
        ProviderRequest request = new ProviderRequest(command.tenantId(), command.commandId(), "WEBHOOK",
                command.payload().getOrDefault("endpointToken", command.subjectToken()),
                command.payload().getOrDefault("action", command.nodeId()),
                providerRoute.endpoint(command.tenantId(), "WEBHOOK"),
                variables, Instant.ofEpochMilli(command.createdAtEpochMillis()));
        ProviderResult result = provider.send(request, ProviderPolicy.defaults());
        if (result.status() != ProviderResult.Status.ACCEPTED) {
            throw new IllegalStateException("webhook provider rejected command: " + result.code());
        }
        return new CommandResult(command.commandId(), "SUCCEEDED", "", result.code());
    }

    private CommandRow claimCommand(JourneyEffectCommand command, String encoded, String payloadHash) {
        EngagementRepository.CommandRecord stored = repository
                .lockCommand(command.tenantId(), command.commandId()).orElse(null);
        if (stored != null) {
            CommandRow current = new CommandRow(stored.effectType(), stored.enrollmentId(), stored.payloadHash(),
                    stored.state(), stored.contactId(), stored.providerCode());
            if (!current.effectType().equals(command.effectType())
                    || !current.enrollmentId().equals(command.enrollmentId())
                    || !current.payloadHash().equals(payloadHash)) {
                throw new ConflictException("ENGAGEMENT_COMMAND_COLLISION",
                        "engagement command id was reused with another payload");
            }
            return current;
        }
        Instant now = clock.instant();
        repository.insertCommand(new EngagementRepository.CommandWrite(command.tenantId(), command.commandId(),
                command.effectType(), command.enrollmentId(), payloadHash, encoded, now));
        return new CommandRow(command.effectType(), command.enrollmentId(), payloadHash,
                "PENDING", "", "");
    }

    private static String required(Map<String, String> payload, String field) {
        String value = payload.getOrDefault(field, "");
        if (value.isBlank()) throw new IllegalArgumentException("journey effect requires " + field);
        return value;
    }

    private static long positiveLong(String value, String field) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 1) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(field + " must be positive", invalid);
        }
    }

    private static String safeMessage(RuntimeException failure) {
        String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        return message.length() <= 1_000 ? message : message.substring(0, 1_000);
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public ContactView callback(ProviderCallback callback) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("provider:callback");
        return transactions.execute(ignored -> {
            String tenantId = scope.tenantId().value();
            boolean duplicate = repository.providerReceiptExists(tenantId, callback.providerEventId());
            ContactView contact = contactByProviderRequest(tenantId, callback.providerRequestId());
            if (duplicate) return contact;
            repository.insertProviderReceipt(tenantId, callback, json(callback.attributes()));
            ContactState target = ContactState.valueOf(callback.status().name());
            if (canAdvance(contact.state(), target)) {
                Instant now = clock.instant();
                repository.updateContactState(tenantId, contact.contactId(), target.name(), now);
                outbox(tenantId, contact.contactId(), "Contact" + target.name(), now);
                return new ContactView(contact.contactId(), contact.contactKey(), target, contact.providerRequestId(),
                        contact.providerCode(), now);
            }
            return contact;
        });
    }

    static boolean inQuietHours(Instant instant, String timezone, LocalTime start, LocalTime end) {
        LocalTime local = ZonedDateTime.ofInstant(instant, ZoneId.of(timezone)).toLocalTime();
        if (start.equals(end)) return false;
        return start.isBefore(end) ? !local.isBefore(start) && local.isBefore(end)
                : !local.isBefore(start) || local.isBefore(end);
    }

    private static boolean canAdvance(ContactState current, ContactState target) {
        if (current == ContactState.UNSUBSCRIBED) return false;
        if (target == ContactState.UNSUBSCRIBED) return true;
        return rank(target) >= rank(current) && !(current == ContactState.DELIVERED && target == ContactState.FAILED);
    }

    private static int rank(ContactState state) {
        return switch (state) {
            case PENDING, RETRY_PENDING -> 0;
            case ACCEPTED -> 1;
            case SENT -> 2;
            case DELIVERED, FAILED -> 3;
            case CLICKED -> 4;
            case UNSUBSCRIBED -> 5;
        };
    }

    private ConsentView consent(String tenantId, String subject, String channel) {
        return repository.findConsent(tenantId, subject, channel)
                .orElseThrow(() -> new ConflictException("CONSENT_REQUIRED", "consent projection is missing"));
    }

    private boolean suppressed(String tenantId, String subject, String channel) {
        return repository.isSuppressed(tenantId, subject, channel, clock.instant());
    }

    private FrequencyPolicy policy(String tenantId, String campaign, String channel) {
        return repository.findFrequencyPolicy(tenantId, campaign, channel)
                .orElseThrow(() -> new ConflictException("FREQUENCY_POLICY_REQUIRED",
                        "frequency policy is missing"));
    }

    private void consumeFrequency(String tenantId, SendRequest request, FrequencyPolicy policy, Instant admittedAt) {
        // requestedAt仅作业务元数据；按实际准入时间扣频控，不能通过回填历史时间绕过当前窗口。
        long bucket = Math.floorDiv(admittedAt.getEpochSecond(), policy.windowSeconds());
        Integer storedCount = repository.lockFrequencyCount(tenantId, request.campaignId(), request.channel(),
                request.subjectToken(), bucket).orElse(null);
        int count = storedCount == null ? 0 : storedCount;
        if (count >= policy.maxContacts()) throw new ConflictException("FREQUENCY_CAP_REACHED", "contact cap reached");
        if (storedCount == null) {
            repository.insertFrequencyBucket(tenantId, request.campaignId(), request.channel(),
                    request.subjectToken(), bucket, clock.instant());
        } else {
            repository.incrementFrequencyBucket(tenantId, request.campaignId(), request.channel(),
                    request.subjectToken(), bucket, clock.instant());
        }
    }

    private TemplateView template(String tenantId, String id, long version) {
        return repository.findTemplate(tenantId, id, version)
                .orElseThrow(() -> new NotFoundException("TEMPLATE_NOT_FOUND", "template version not found"));
    }

    private java.util.Optional<ContactView> contactByKey(String tenantId, String key) {
        return repository.findContactByKey(tenantId, key);
    }
    private ContactView contactById(String tenantId, String id) {
        return repository.findContactById(tenantId, id)
                .orElseThrow(() -> new NotFoundException("CONTACT_NOT_FOUND", "contact not found"));
    }
    private ContactView contactByProviderRequest(String tenantId, String requestId) {
        return repository.lockContactByProviderRequest(tenantId, requestId)
                .orElseThrow(() -> new NotFoundException("CONTACT_NOT_FOUND", "provider request is unknown"));
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JacksonException failure) { throw new IllegalArgumentException("engagement payload cannot be serialized", failure); }
    }

    /** 递归排序JSON对象键，保留字段类型与数组顺序；渠道地址由服务端治理，不属于客户端命令。 */
    private String requestFingerprint(SendRequest request) {
        ContactIdentity identity = new ContactIdentity(request.subjectToken(), request.campaignId(), request.channel(),
                request.recipientToken(), request.templateId(), request.templateVersion(), request.variables(),
                request.timezone(), request.personalized(), request.requestedAt());
        try {
            return Digests.sha256Hex(requestWriter.writeValueAsString(identity));
        } catch (JacksonException failure) {
            throw new IllegalStateException("cannot encode contact identity", failure);
        }
    }

    /** 指纹仅存摘要，不新增收件人明文存储或扩展查询响应。 */
    private record ContactIdentity(String subjectToken, String campaignId, String channel, String recipientToken,
            String templateId, long templateVersion, Map<String, Object> variables, String timezone,
            boolean personalized, Instant requestedAt) { }

    private void outbox(String tenantId, String contactId, String eventType, Instant now) {
        long sequence = nextOutboxSequence(tenantId, contactId, now);
        String eventId = UUID.randomUUID().toString();
        repository.insertOutbox(new EngagementRepository.OutboxWrite(tenantId, eventId, contactId, eventType,
                "mk.engagement.event.v1", tenantId + ':' + contactId, sequence,
                json(Map.of("eventId", eventId, "eventType", eventType, "tenantId", tenantId,
                        "contactId", contactId, "occurredAt", format(now))), now));
    }

    private long nextOutboxSequence(String tenantId, String contactId, Instant now) {
        boolean updated = repository.incrementOutboxSequence(tenantId, contactId, now);
        if (!updated && !repository.insertInitialOutboxSequence(tenantId, contactId, now)) {
            repository.incrementOutboxSequence(tenantId, contactId, now);
        }
        return repository.findOutboxSequence(tenantId, contactId)
                .orElseThrow(() -> new IllegalStateException("engagement outbox sequence allocation failed"));
    }
    public enum ContactState { PENDING, RETRY_PENDING, ACCEPTED, SENT, DELIVERED, FAILED, CLICKED, UNSUBSCRIBED }
    private record Pending(boolean created, ContactView view) { }
    private record CommandRow(String effectType, String enrollmentId, String payloadHash, String state,
            String contactId, String providerCode) { }
    public record CommandResult(String commandId, String state, String contactId, String providerCode) { }
    public record ConsentRequest(String subjectToken, String channel, boolean allowed, boolean minor,
            boolean personalizationAllowed, String source, Instant effectiveAt) { }
    public record ConsentView(String subjectToken, String channel, boolean allowed, boolean minor,
            boolean personalizationAllowed, long version, Instant effectiveAt) { }
    public record SuppressionRequest(String subjectToken, String channel, String reason, Instant expiresAt) { }
    public record SuppressionView(String subjectToken, String channel, String reason, Instant expiresAt) { }
    public record FrequencyPolicy(String campaignId, String channel, long windowSeconds, int maxContacts,
            LocalTime quietStart, LocalTime quietEnd) {
        public FrequencyPolicy {
            if (windowSeconds < 1 || maxContacts < 1 || quietStart == null || quietEnd == null)
                throw new IllegalArgumentException("frequency policy is invalid");
        }
    }
    public record TemplateRequest(String templateId, long version, String channel, String content,
            Set<String> requiredVariables) {
        public TemplateRequest {
            // 结构性无效项交给统一输入校验，避免反序列化时抛NPE而丢失稳定错误码。
            requiredVariables = requiredVariables == null ? null
                    : java.util.Collections.unmodifiableSet(new java.util.LinkedHashSet<>(requiredVariables));
        }
    }
    public record TemplateView(String templateId, long version, String channel, String content,
            Set<String> requiredVariables, String state, Instant createdAt) { }
    public record SendRequest(String contactKey, String subjectToken, String campaignId, String channel,
            String recipientToken, String templateId, long templateVersion, Map<String, Object> variables,
            String timezone, boolean personalized, Instant requestedAt, URI providerEndpoint) {
        public SendRequest { variables = Map.copyOf(variables == null ? Map.of() : variables); }
    }
    public record ContactView(String contactId, String contactKey, ContactState state,
            String providerRequestId, String providerCode, Instant updatedAt) { }
}
