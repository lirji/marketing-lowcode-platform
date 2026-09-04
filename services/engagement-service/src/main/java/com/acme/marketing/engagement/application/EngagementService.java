package com.acme.marketing.engagement.application;

import static com.acme.marketing.platform.time.SqlTime.format;

import com.acme.marketing.contracts.event.JourneyEffectCommand;
import com.acme.marketing.platform.crypto.Digests;
import com.acme.marketing.platform.error.ConflictException;
import com.acme.marketing.platform.error.NotFoundException;
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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public final class EngagementService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final ProviderConnector provider;
    private final ProviderRoute providerRoute;
    private final TransactionTemplate transactions;

    public EngagementService(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock,
            ProviderConnector provider, ProviderRoute providerRoute, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
        this.provider = provider;
        this.providerRoute = providerRoute;
        this.transactions = transactions;
    }

    public ConsentView setConsent(ConsentRequest request) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("consent:write");
        return transactions.execute(ignored -> {
            Long current = jdbc.query("select version_no from mk_consent where tenant_id=? and subject_token=? and channel_name=? for update",
                    rs -> rs.next() ? rs.getLong(1) : null, scope.tenantId().value(), request.subjectToken(), request.channel());
            long version = current == null ? 1 : current + 1;
            Instant now = clock.instant();
            if (current == null) {
                jdbc.update("insert into mk_consent(tenant_id,subject_token,channel_name,allowed_value,minor_value,personalization_allowed,version_no,source_name,effective_at,updated_at) values(?,?,?,?,?,?,?,?,?,?)",
                        scope.tenantId().value(), request.subjectToken(), request.channel(), request.allowed(), request.minor(),
                        request.personalizationAllowed(), version, request.source(), format(request.effectiveAt()), format(now));
            } else {
                jdbc.update("update mk_consent set allowed_value=?,minor_value=?,personalization_allowed=?,version_no=?,source_name=?,effective_at=?,updated_at=? where tenant_id=? and subject_token=? and channel_name=?",
                        request.allowed(), request.minor(), request.personalizationAllowed(), version, request.source(),
                        format(request.effectiveAt()), format(now), scope.tenantId().value(),
                        request.subjectToken(), request.channel());
            }
            return new ConsentView(request.subjectToken(), request.channel(), request.allowed(), request.minor(),
                    request.personalizationAllowed(), version, request.effectiveAt());
        });
    }

    public SuppressionView suppress(SuppressionRequest request) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("suppression:write");
        return transactions.execute(ignored -> {
            int updated = jdbc.update("update mk_suppression set reason_text=?,expires_at=?,updated_at=? where tenant_id=? and subject_token=? and channel_name=?",
                    request.reason(), request.expiresAt() == null ? null : format(request.expiresAt()),
                    format(clock.instant()), scope.tenantId().value(), request.subjectToken(), request.channel());
            if (updated == 0) {
                jdbc.update("insert into mk_suppression(tenant_id,subject_token,channel_name,reason_text,expires_at,updated_at) values(?,?,?,?,?,?)",
                        scope.tenantId().value(), request.subjectToken(), request.channel(), request.reason(),
                        request.expiresAt() == null ? null : format(request.expiresAt()), format(clock.instant()));
            }
            return new SuppressionView(request.subjectToken(), request.channel(), request.reason(), request.expiresAt());
        });
    }

    public FrequencyPolicy setPolicy(FrequencyPolicy policy) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("engagement-policy:write");
        transactions.executeWithoutResult(ignored -> {
            int updated = jdbc.update("update mk_frequency_policy set window_seconds=?,max_contacts=?,quiet_start=?,quiet_end=?,updated_at=? where tenant_id=? and campaign_id=? and channel_name=?",
                    policy.windowSeconds(), policy.maxContacts(), policy.quietStart().toString(), policy.quietEnd().toString(),
                    format(clock.instant()), scope.tenantId().value(), policy.campaignId(), policy.channel());
            if (updated == 0) jdbc.update("insert into mk_frequency_policy(tenant_id,campaign_id,channel_name,window_seconds,max_contacts,quiet_start,quiet_end,updated_at) values(?,?,?,?,?,?,?,?)",
                    scope.tenantId().value(), policy.campaignId(), policy.channel(), policy.windowSeconds(),
                    policy.maxContacts(), policy.quietStart().toString(), policy.quietEnd().toString(), format(clock.instant()));
        });
        return policy;
    }

    public TemplateView createTemplate(TemplateRequest request) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("template:write");
        if (!request.content().contains("unsubscribe") && "EMAIL".equals(request.channel())) {
            throw new ConflictException("UNSUBSCRIBE_NOTICE_REQUIRED", "email template requires unsubscribe text");
        }
        Instant now = clock.instant();
        transactions.executeWithoutResult(ignored -> jdbc.update("insert into mk_template_version(tenant_id,template_id,version_no,channel_name,content_text,required_variables,state_name,created_by,created_at) values(?,?,?,?,?,?,?,?,?)",
                scope.tenantId().value(), request.templateId(), request.version(), request.channel(), request.content(),
                String.join(",", request.requiredVariables()), "APPROVED", scope.actorId(), format(now)));
        return new TemplateView(request.templateId(), request.version(), request.channel(), request.content(),
                request.requiredVariables(), "APPROVED", now);
    }

    public List<TemplateView> templates() {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("template:read");
        return jdbc.query("select template_id,version_no,channel_name,content_text,required_variables,state_name,created_at from mk_template_version current where tenant_id=? and version_no=(select max(latest.version_no) from mk_template_version latest where latest.tenant_id=current.tenant_id and latest.template_id=current.template_id) order by template_id",
                (rs, rowNum) -> new TemplateView(rs.getString(1), rs.getLong(2), rs.getString(3),
                        rs.getString(4), csv(rs.getString(5)), rs.getString(6), Instant.parse(rs.getString(7))),
                scope.tenantId().value());
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
        List<ContactView> contacts = contactByKey(scope.tenantId().value(), contactKey);
        if (contacts.isEmpty()) throw new NotFoundException("CONTACT_NOT_FOUND", "contact not found");
        return contacts.getFirst();
    }

    public List<ContactView> contacts(String state, int limit) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("contact:read");
        if (limit < 1 || limit > 200) throw new IllegalArgumentException("limit must be in [1,200]");
        StringBuilder sql = new StringBuilder(
                "select contact_id,contact_key,state_name,provider_request_id,provider_code,updated_at from mk_contact_attempt where tenant_id=?");
        List<Object> arguments = new java.util.ArrayList<>();
        arguments.add(scope.tenantId().value());
        if (state != null && !state.isBlank()) {
            ContactState parsed;
            try {
                parsed = ContactState.valueOf(state.toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("contact state is invalid", invalid);
            }
            sql.append(" and state_name=?");
            arguments.add(parsed.name());
        }
        sql.append(" order by updated_at desc,contact_id limit ?");
        arguments.add(limit);
        return jdbc.query(sql.toString(), (rs, rowNum) -> new ContactView(rs.getString(1), rs.getString(2),
                ContactState.valueOf(rs.getString(3)), value(rs.getString(4)), value(rs.getString(5)),
                Instant.parse(rs.getString(6))), arguments.toArray());
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
            transactions.executeWithoutResult(ignored -> jdbc.update(
                    "update mk_engagement_command set state_name='SUCCEEDED',contact_id=?,provider_code=?,last_error='',updated_at=? where tenant_id=? and command_id=?",
                    emptyToNull(result.contactId()), result.providerCode(), format(clock.instant()),
                    command.tenantId(), command.commandId()));
            return result;
        } catch (RuntimeException failure) {
            transactions.executeWithoutResult(ignored -> jdbc.update(
                    "update mk_engagement_command set state_name='RETRY_PENDING',last_error=?,updated_at=? where tenant_id=? and command_id=?",
                    safeMessage(failure), format(clock.instant()), command.tenantId(), command.commandId()));
            throw failure;
        }
    }

    private ContactView sendForTenant(String tenantId, SendRequest request) {
        Pending pending = transactions.execute(ignored -> prepare(tenantId, request));
        if (pending == null) throw new IllegalStateException("contact transaction returned no result");
        if (!pending.created()) return pending.view();
        ProviderRequest providerRequest = new ProviderRequest(tenantId, request.contactKey(),
                request.channel(), request.recipientToken(), request.templateId() + ':' + request.templateVersion(),
                providerRoute.endpoint(tenantId, request.channel()), request.variables(), clock.instant());
        ProviderResult result = provider.send(providerRequest, ProviderPolicy.defaults());
        return transactions.execute(ignored -> complete(tenantId, pending.view().contactId(), result));
    }

    private Pending prepare(String tenantId, SendRequest request) {
        List<ContactView> existing = contactByKey(tenantId, request.contactKey());
        if (!existing.isEmpty()) {
            ContactView current = existing.getFirst();
            return new Pending(current.state() == ContactState.PENDING
                    || current.state() == ContactState.RETRY_PENDING, current);
        }
        ConsentView consent = consent(tenantId, request.subjectToken(), request.channel());
        if (!consent.allowed() || consent.effectiveAt().isAfter(clock.instant())) {
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
        if (inQuietHours(request.requestedAt(), request.timezone(), policy.quietStart(), policy.quietEnd())) {
            throw new ConflictException("QUIET_HOURS", "contact is inside subject quiet hours");
        }
        TemplateView template = template(tenantId, request.templateId(), request.templateVersion());
        if (!template.channel().equals(request.channel()) || !request.variables().keySet().containsAll(template.requiredVariables())) {
            throw new ConflictException("TEMPLATE_VARIABLES_INVALID", "required template variables are missing");
        }
        consumeFrequency(tenantId, request, policy);
        String contactId = UUID.randomUUID().toString();
        Instant now = clock.instant();
        jdbc.update("insert into mk_contact_attempt(tenant_id,contact_id,contact_key,subject_token,campaign_id,channel_name,template_id,template_version,state_name,variables_json,requested_at,updated_at) values(?,?,?,?,?,?,?,?,?,?,?,?)",
                tenantId, contactId, request.contactKey(), request.subjectToken(), request.campaignId(), request.channel(),
                request.templateId(), request.templateVersion(), ContactState.PENDING.name(), json(request.variables()),
                format(request.requestedAt()), format(now));
        outbox(tenantId, contactId, "ContactRequested", now);
        return new Pending(true, new ContactView(contactId, request.contactKey(), ContactState.PENDING, "", "", now));
    }

    private ContactView complete(String tenantId, String contactId, ProviderResult result) {
        ContactState state = switch (result.status()) {
            case ACCEPTED -> ContactState.ACCEPTED;
            case RETRY_EXHAUSTED, RATE_LIMITED, CIRCUIT_OPEN -> ContactState.RETRY_PENDING;
            case PERMANENT_FAILURE -> ContactState.FAILED;
        };
        jdbc.update("update mk_contact_attempt set state_name=?,provider_request_id=?,provider_code=?,updated_at=? where tenant_id=? and contact_id=?",
                state.name(), result.providerRequestId(), result.code(), format(clock.instant()), tenantId, contactId);
        if (state == ContactState.FAILED) {
            jdbc.update("insert into mk_contact_dlq(tenant_id,dlq_id,contact_id,reason_code,state_name,created_at) values(?,?,?,?,?,?)",
                    tenantId, UUID.randomUUID().toString(), contactId, result.code(), "OPEN", format(clock.instant()));
        }
        outbox(tenantId, contactId, "Contact" + state.name(), clock.instant());
        return new ContactView(contactId, contactById(tenantId, contactId).contactKey(), state,
                result.providerRequestId(), result.code(), clock.instant());
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
        List<CommandRow> existing = jdbc.query("select effect_type,enrollment_id,payload_hash,state_name,contact_id,provider_code from mk_engagement_command where tenant_id=? and command_id=? for update",
                (rs, rowNum) -> new CommandRow(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), value(rs.getString(5)), value(rs.getString(6))),
                command.tenantId(), command.commandId());
        if (!existing.isEmpty()) {
            CommandRow current = existing.getFirst();
            if (!current.effectType().equals(command.effectType())
                    || !current.enrollmentId().equals(command.enrollmentId())
                    || !current.payloadHash().equals(payloadHash)) {
                throw new ConflictException("ENGAGEMENT_COMMAND_COLLISION",
                        "engagement command id was reused with another payload");
            }
            return current;
        }
        Instant now = clock.instant();
        jdbc.update("insert into mk_engagement_command(tenant_id,command_id,effect_type,enrollment_id,payload_hash,payload_json,state_name,created_at,updated_at) values(?,?,?,?,?,?,?,?,?)",
                command.tenantId(), command.commandId(), command.effectType(), command.enrollmentId(),
                payloadHash, encoded, "PENDING", format(now), format(now));
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
            Integer duplicate = jdbc.query("select count(*) from mk_provider_receipt where tenant_id=? and provider_event_id=?",
                    rs -> rs.next() ? rs.getInt(1) : 0, scope.tenantId().value(), callback.providerEventId());
            ContactView contact = contactByProviderRequest(scope.tenantId().value(), callback.providerRequestId());
            if (duplicate != null && duplicate > 0) return contact;
            jdbc.update("insert into mk_provider_receipt(tenant_id,provider_event_id,provider_request_id,status_name,occurred_at,attributes_json) values(?,?,?,?,?,?)",
                    scope.tenantId().value(), callback.providerEventId(), callback.providerRequestId(),
                    callback.status().name(), format(callback.occurredAt()), json(callback.attributes()));
            ContactState target = ContactState.valueOf(callback.status().name());
            if (canAdvance(contact.state(), target)) {
                jdbc.update("update mk_contact_attempt set state_name=?,updated_at=? where tenant_id=? and contact_id=?",
                        target.name(), format(clock.instant()), scope.tenantId().value(), contact.contactId());
                outbox(scope.tenantId().value(), contact.contactId(), "Contact" + target.name(), clock.instant());
                return new ContactView(contact.contactId(), contact.contactKey(), target, contact.providerRequestId(),
                        contact.providerCode(), clock.instant());
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
        List<ConsentView> rows = jdbc.query("select allowed_value,minor_value,personalization_allowed,version_no,effective_at from mk_consent where tenant_id=? and subject_token=? and channel_name=?",
                (rs, rowNum) -> new ConsentView(subject, channel, rs.getBoolean(1), rs.getBoolean(2),
                        rs.getBoolean(3), rs.getLong(4), Instant.parse(rs.getString(5))), tenantId, subject, channel);
        if (rows.isEmpty()) throw new ConflictException("CONSENT_REQUIRED", "consent projection is missing");
        return rows.getFirst();
    }

    private boolean suppressed(String tenantId, String subject, String channel) {
        Integer count = jdbc.query("select count(*) from mk_suppression where tenant_id=? and subject_token=? and (channel_name=? or channel_name='*') and (expires_at is null or expires_at>?)",
                rs -> rs.next() ? rs.getInt(1) : 0, tenantId, subject, channel, format(clock.instant()));
        return count != null && count > 0;
    }

    private FrequencyPolicy policy(String tenantId, String campaign, String channel) {
        List<FrequencyPolicy> rows = jdbc.query("select window_seconds,max_contacts,quiet_start,quiet_end from mk_frequency_policy where tenant_id=? and campaign_id=? and channel_name=?",
                (rs, rowNum) -> new FrequencyPolicy(campaign, channel, rs.getLong(1), rs.getInt(2),
                        LocalTime.parse(rs.getString(3)), LocalTime.parse(rs.getString(4))), tenantId, campaign, channel);
        if (rows.isEmpty()) throw new ConflictException("FREQUENCY_POLICY_REQUIRED", "frequency policy is missing");
        return rows.getFirst();
    }

    private void consumeFrequency(String tenantId, SendRequest request, FrequencyPolicy policy) {
        long bucket = Math.floorDiv(request.requestedAt().getEpochSecond(), policy.windowSeconds());
        List<Integer> counts = jdbc.query("select contact_count from mk_frequency_bucket where tenant_id=? and campaign_id=? and channel_name=? and subject_token=? and window_bucket=? for update",
                (rs, rowNum) -> rs.getInt(1), tenantId, request.campaignId(), request.channel(),
                request.subjectToken(), bucket);
        int count = counts.isEmpty() ? 0 : counts.getFirst();
        if (count >= policy.maxContacts()) throw new ConflictException("FREQUENCY_CAP_REACHED", "contact cap reached");
        if (counts.isEmpty()) jdbc.update("insert into mk_frequency_bucket(tenant_id,campaign_id,channel_name,subject_token,window_bucket,contact_count,updated_at) values(?,?,?,?,?,?,?)",
                tenantId, request.campaignId(), request.channel(), request.subjectToken(), bucket, 1, format(clock.instant()));
        else jdbc.update("update mk_frequency_bucket set contact_count=contact_count+1,updated_at=? where tenant_id=? and campaign_id=? and channel_name=? and subject_token=? and window_bucket=?",
                format(clock.instant()), tenantId, request.campaignId(), request.channel(), request.subjectToken(), bucket);
    }

    private TemplateView template(String tenantId, String id, long version) {
        List<TemplateView> rows = jdbc.query("select channel_name,content_text,required_variables,state_name,created_at from mk_template_version where tenant_id=? and template_id=? and version_no=?",
                (rs, rowNum) -> new TemplateView(id, version, rs.getString(1), rs.getString(2), csv(rs.getString(3)),
                        rs.getString(4), Instant.parse(rs.getString(5))), tenantId, id, version);
        if (rows.isEmpty()) throw new NotFoundException("TEMPLATE_NOT_FOUND", "template version not found");
        return rows.getFirst();
    }

    private List<ContactView> contactByKey(String tenantId, String key) {
        return jdbc.query("select contact_id,state_name,provider_request_id,provider_code,updated_at from mk_contact_attempt where tenant_id=? and contact_key=?",
                (rs, rowNum) -> new ContactView(rs.getString(1), key, ContactState.valueOf(rs.getString(2)),
                        value(rs.getString(3)), value(rs.getString(4)), Instant.parse(rs.getString(5))), tenantId, key);
    }
    private ContactView contactById(String tenantId, String id) {
        List<ContactView> rows = jdbc.query("select contact_key,state_name,provider_request_id,provider_code,updated_at from mk_contact_attempt where tenant_id=? and contact_id=?",
                (rs, rowNum) -> new ContactView(id, rs.getString(1), ContactState.valueOf(rs.getString(2)),
                        value(rs.getString(3)), value(rs.getString(4)), Instant.parse(rs.getString(5))), tenantId, id);
        if (rows.isEmpty()) throw new NotFoundException("CONTACT_NOT_FOUND", "contact not found");
        return rows.getFirst();
    }
    private ContactView contactByProviderRequest(String tenantId, String requestId) {
        List<ContactView> rows = jdbc.query("select contact_id,contact_key,state_name,provider_code,updated_at from mk_contact_attempt where tenant_id=? and provider_request_id=? for update",
                (rs, rowNum) -> new ContactView(rs.getString(1), rs.getString(2), ContactState.valueOf(rs.getString(3)),
                        requestId, value(rs.getString(4)), Instant.parse(rs.getString(5))), tenantId, requestId);
        if (rows.isEmpty()) throw new NotFoundException("CONTACT_NOT_FOUND", "provider request is unknown");
        return rows.getFirst();
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JacksonException failure) { throw new IllegalArgumentException("engagement payload cannot be serialized", failure); }
    }

    private void outbox(String tenantId, String contactId, String eventType, Instant now) {
        long sequence = nextOutboxSequence(tenantId, contactId, now);
        String eventId = UUID.randomUUID().toString();
        jdbc.update("insert into mk_engagement_outbox(tenant_id,event_id,contact_id,event_type,destination_topic,partition_key,stream_sequence,payload_json,next_attempt_at,created_at) values(?,?,?,?,?,?,?,?,?,?)",
                tenantId, eventId, contactId, eventType, "mk.engagement.event.v1",
                tenantId + ':' + contactId, sequence,
                json(Map.of("eventId", eventId, "eventType", eventType, "tenantId", tenantId,
                        "contactId", contactId, "occurredAt", format(now))), format(now), format(now));
    }

    private long nextOutboxSequence(String tenantId, String contactId, Instant now) {
        int updated = jdbc.update("update mk_engagement_outbox_position set last_sequence=last_sequence+1,updated_at=? where tenant_id=? and contact_id=?",
                format(now), tenantId, contactId);
        if (updated == 0) {
            try {
                jdbc.update("insert into mk_engagement_outbox_position(tenant_id,contact_id,last_sequence,updated_at) values(?,?,?,?)",
                        tenantId, contactId, 1, format(now));
            } catch (DuplicateKeyException race) {
                jdbc.update("update mk_engagement_outbox_position set last_sequence=last_sequence+1,updated_at=? where tenant_id=? and contact_id=?",
                        format(now), tenantId, contactId);
            }
        }
        Long sequence = jdbc.query("select last_sequence from mk_engagement_outbox_position where tenant_id=? and contact_id=?",
                rs -> rs.next() ? rs.getLong(1) : null, tenantId, contactId);
        if (sequence == null) throw new IllegalStateException("engagement outbox sequence allocation failed");
        return sequence;
    }
    private static Set<String> csv(String encoded) {
        return encoded == null || encoded.isBlank() ? Set.of() : Set.copyOf(Arrays.asList(encoded.split(",")));
    }
    private static String value(String value) { return value == null ? "" : value; }

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
        public TemplateRequest { requiredVariables = Set.copyOf(requiredVariables); }
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
