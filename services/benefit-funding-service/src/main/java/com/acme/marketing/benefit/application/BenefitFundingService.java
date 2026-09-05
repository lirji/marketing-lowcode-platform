package com.acme.marketing.benefit.application;

import static com.acme.marketing.platform.time.SqlTime.format;

import com.acme.marketing.benefit.application.BenefitSkuCatalog.BenefitSkuView;
import com.acme.marketing.benefit.application.BenefitSkuCatalog.SkuStatus;
import com.acme.marketing.benefit.domain.ResourceAccount;
import com.acme.marketing.contracts.event.JourneyEffectCommand;
import com.acme.marketing.contracts.offer.FundingShareClaim;
import com.acme.marketing.contracts.offer.OfferLineClaim;
import com.acme.marketing.contracts.offer.OfferTokenClaims;
import com.acme.marketing.contracts.offer.OfferTokenCodec;
import com.acme.marketing.platform.crypto.Digests;
import com.acme.marketing.platform.error.ConflictException;
import com.acme.marketing.platform.error.NotFoundException;
import com.acme.marketing.platform.identity.TenantId;
import com.acme.marketing.platform.identity.TenantScope;
import com.acme.marketing.platform.web.TenantContextHolder;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class BenefitFundingService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final OfferTokenTrust tokenTrust;
    private final BenefitSkuCatalog benefitSkuCatalog;
    private final TransactionTemplate isolatedTransactions;

    public BenefitFundingService(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock, OfferTokenTrust tokenTrust,
            BenefitSkuCatalog benefitSkuCatalog, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
        this.tokenTrust = tokenTrust;
        this.benefitSkuCatalog = benefitSkuCatalog;
        this.isolatedTransactions = new TransactionTemplate(transactionManager);
        this.isolatedTransactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional
    public AccountView createAccount(CreateAccountRequest request) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("funding:account-write");
        ResourceAccount account = new ResourceAccount(request.resourceKey(), request.type(), request.currency(),
                request.authorized(), request.authorized(), 0, 0, 0, request.fencingEpoch(), 0,
                ResourceAccount.State.ACTIVE);
        jdbc.update("insert into mk_resource_account(tenant_id,resource_key,resource_type,currency_code,authorized_amount,available_amount,reserved_amount,consumed_amount,returned_amount,fencing_epoch,version_no,state_name,updated_at) values(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                scope.tenantId().value(), account.resourceKey(), account.type().name(), account.currency(),
                account.authorized(), account.available(), account.reserved(), account.consumed(), account.returned(),
                account.fencingEpoch(), account.version(), account.state().name(), format(clock.instant()));
        return AccountView.from(account);
    }

    @Transactional(readOnly = true)
    public List<AccountView> accounts() {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("funding:account-read");
        return jdbc.query("select resource_key,resource_type,currency_code,authorized_amount,available_amount,reserved_amount,consumed_amount,returned_amount,fencing_epoch,version_no,state_name from mk_resource_account where tenant_id=? order by resource_key",
                (rs, rowNum) -> AccountView.from(resourceAccount(rs)), scope.tenantId().value());
    }

    @Transactional(readOnly = true)
    public List<BenefitView> benefits() {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("benefit:read");
        return jdbc.query("select benefit_id,version_no,name_text,status_name,resource_key,benefit_sku_id,policy_json,created_by,created_at from mk_benefit_definition current where tenant_id=? and version_no=(select max(latest.version_no) from mk_benefit_definition latest where latest.tenant_id=current.tenant_id and latest.benefit_id=current.benefit_id) order by name_text,benefit_id",
                (rs, rowNum) -> benefitView(rs), scope.tenantId().value());
    }

    @Transactional(readOnly = true)
    public BenefitView benefit(String benefitId) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("benefit:read");
        List<BenefitView> rows = jdbc.query("select benefit_id,version_no,name_text,status_name,resource_key,benefit_sku_id,policy_json,created_by,created_at from mk_benefit_definition where tenant_id=? and benefit_id=? order by version_no desc limit 1",
                (rs, rowNum) -> benefitView(rs), scope.tenantId().value(), benefitId);
        if (rows.isEmpty()) throw new NotFoundException("BENEFIT_NOT_FOUND", "benefit not found");
        return rows.getFirst();
    }

    @Transactional
    public BenefitView putBenefit(String benefitId, String commandId, BenefitRequest request) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("benefit:write");
        if (benefitId == null || benefitId.isBlank() || benefitId.length() > 128) {
            throw new IllegalArgumentException("benefit id is invalid");
        }
        String payloadHash = Digests.sha256Hex("BENEFIT_PUT|" + benefitId + '|' + json(request));
        return command(scope.tenantId().value(), commandId, payloadHash, BenefitView.class,
                () -> {
                    // 只让首次幂等命令执行发布校验；成功命令回放必须返回原响应，不受后续模板暂停影响。
                    if (request.status() == BenefitStatus.ACTIVE) {
                        if (request.benefitSkuId() == null) {
                            throw new ConflictException("SKU_NOT_ACTIVE",
                                    "ACTIVE benefit must bind an ACTIVE benefit SKU");
                        }
                        benefitSkuCatalog.requireActive(scope.tenantId().value(), request.benefitSkuId());
                    }
                    return putBenefitNow(scope.tenantId().value(), scope.actorId(), benefitId, request);
                });
    }

    private BenefitView putBenefitNow(String tenantId, String actorId, String benefitId, BenefitRequest request) {
        jdbc.update("insert into mk_benefit_definition_head(tenant_id,benefit_id,latest_version) values(?,?,?) on duplicate key update benefit_id=benefit_id",
                tenantId, benefitId, 0);
        Long current = jdbc.query("select latest_version from mk_benefit_definition_head where tenant_id=? and benefit_id=? for update",
                rs -> rs.next() ? rs.getLong(1) : null, tenantId, benefitId);
        if (current == null) throw new IllegalStateException("benefit version head was not created");
        long version = Math.addExact(current, 1);
        if (!request.resourceKey().isBlank()) {
            Integer resources = jdbc.query("select count(*) from mk_resource_account where tenant_id=? and resource_key=?",
                    rs -> rs.next() ? rs.getInt(1) : 0, tenantId, request.resourceKey());
            if (resources == null || resources == 0) {
                throw new ConflictException("BENEFIT_RESOURCE_NOT_FOUND", request.resourceKey());
            }
        }
        Instant now = clock.instant();
        jdbc.update("insert into mk_benefit_definition(tenant_id,benefit_id,version_no,name_text,status_name,resource_key,benefit_sku_id,policy_json,created_by,created_at) values(?,?,?,?,?,?,?,?,?,?)",
                tenantId, benefitId, version, request.name(), request.status().name(), request.resourceKey(),
                request.benefitSkuId(), json(request.policy()), actorId, format(now));
        jdbc.update("update mk_benefit_definition_head set latest_version=? where tenant_id=? and benefit_id=?",
                version, tenantId, benefitId);
        return new BenefitView(benefitId, version, request.name(), request.status(), request.resourceKey(),
                request.benefitSkuId(), request.policy(), actorId, now);
    }

    /** 查询权益中台模板；状态缺省为 ACTIVE，供营销编辑器选择可投放 SKU。 */
    public List<BenefitSkuView> benefitSkus(SkuStatus status) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("benefit:read");
        return benefitSkuCatalog.list(scope.tenantId().value(), status == null ? SkuStatus.ACTIVE : status);
    }

    @Transactional
    public AccountView advanceFence(String resourceKey, FencingLeaseRequest request) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("funding:account-write");
        List<ResourceAccount> rows = lockAccounts(scope.tenantId().value(), List.of(resourceKey));
        if (rows.isEmpty()) throw new NotFoundException("RESOURCE_NOT_FOUND", resourceKey);
        ResourceAccount current = rows.getFirst();
        if (current.fencingEpoch() != request.expectedEpoch()) {
            throw new ConflictException("FENCING_EPOCH_MISMATCH", resourceKey);
        }
        ResourceAccount next = new ResourceAccount(current.resourceKey(), current.type(), current.currency(),
                current.authorized(), current.available(), current.reserved(), current.consumed(), current.returned(),
                Math.addExact(current.fencingEpoch(), 1), Math.addExact(current.version(), 1), request.state());
        int updated = jdbc.update("update mk_resource_account set fencing_epoch=?,version_no=?,state_name=?,updated_at=? where tenant_id=? and resource_key=? and fencing_epoch=? and version_no=?",
                next.fencingEpoch(), next.version(), next.state().name(), format(clock.instant()),
                scope.tenantId().value(), resourceKey, current.fencingEpoch(), current.version());
        if (updated != 1) throw new ConflictException("RESOURCE_CONCURRENT_MODIFICATION", resourceKey);
        return AccountView.from(next);
    }

    @Transactional
    public JourneyGrantView grantFromJourney(JourneyEffectCommand command) {
        if (!"GRANT".equals(command.effectType())) {
            throw new IllegalArgumentException("benefit service only accepts GRANT journey commands");
        }
        String payloadHash = Digests.sha256Hex(json(command));
        return command(command.tenantId(), command.commandId(), payloadHash, JourneyGrantView.class,
                () -> grantJourneyNow(command));
    }

    private JourneyGrantView grantJourneyNow(JourneyEffectCommand command) {
        String resourceKey = command.payload().getOrDefault("resourceKey", "");
        if (resourceKey.isBlank()) throw new IllegalArgumentException("journey GRANT requires resourceKey");
        long quantity = positiveLong(command.payload().getOrDefault("quantity", "1"), "quantity");
        List<ResourceAccount> rows = lockAccounts(command.tenantId(), List.of(resourceKey));
        if (rows.isEmpty()) throw new NotFoundException("RESOURCE_NOT_FOUND", resourceKey);
        ResourceAccount account = rows.getFirst();
        if (account.type() == ResourceAccount.Type.BUDGET) {
            throw new ConflictException("JOURNEY_GRANT_RESOURCE_INVALID",
                    "journey grants require an inventory or prize resource");
        }
        long expectedEpoch = command.payload().containsKey("expectedFencingEpoch")
                ? positiveLong(command.payload().get("expectedFencingEpoch"), "expectedFencingEpoch")
                : account.fencingEpoch();
        ResourceAccount next = account.grant(quantity, expectedEpoch);
        saveAccount(command.tenantId(), next);
        Instant now = clock.instant();
        jdbc.update("insert into mk_journey_benefit_grant(tenant_id,command_id,enrollment_id,subject_token,journey_id,journey_version,resource_key,benefit_id,quantity_value,fencing_epoch,state_name,created_at) values(?,?,?,?,?,?,?,?,?,?,?,?)",
                command.tenantId(), command.commandId(), command.enrollmentId(), command.subjectToken(),
                command.journeyId(), command.journeyVersion(), resourceKey,
                command.payload().getOrDefault("benefitId", resourceKey), quantity, next.fencingEpoch(),
                "GRANTED", format(now));
        ledger(command.tenantId(), command.commandId(), command.enrollmentId(), resourceKey,
                "GRANT", "AVAILABLE", "CONSUMED", quantity, next.version(), null, now);
        outbox(command.tenantId(), command.commandId(), "JourneyBenefitGranted", now);
        return new JourneyGrantView(command.commandId(), command.enrollmentId(), resourceKey,
                command.payload().getOrDefault("benefitId", resourceKey), quantity, next.fencingEpoch(), now);
    }

    @Transactional
    public ApplicationView reserve(String commandId, ReserveRequest request) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("promotion:reserve");
        String payloadHash = Digests.sha256Hex(json(request));
        return command(scope.tenantId().value(), commandId, payloadHash, ApplicationView.class,
                () -> reserveNow(scope, request));
    }

    private ApplicationView reserveNow(TenantScope scope, ReserveRequest request) {
        String tenantId = scope.tenantId().value();
        OfferTokenClaims claims = OfferTokenCodec.verify(request.offerToken(), tokenTrust::resolve,
                new TenantId(tenantId), request.cartDigest(), clock);
        scope.requireOrganization(claims.organizationId());
        claims.shopIds().forEach(scope::requireShop);
        if (!claims.orderId().isBlank() && !request.orderId().equals(claims.orderId())) {
            throw new ConflictException("OFFER_TOKEN_ORDER_MISMATCH", "offer token belongs to another order");
        }
        Map<String, ResourceDemand> demands = demands(claims);
        if (!request.expectedFencingEpochs().keySet().equals(demands.keySet())) {
            throw new ConflictException("FENCING_EPOCHS_INCOMPLETE",
                    "every reserved resource requires exactly one fencing epoch");
        }
        List<ResourceAccount> accounts = lockAccounts(tenantId, demands.keySet().stream().sorted().toList());
        Map<String, ResourceAccount> byKey = new LinkedHashMap<>();
        accounts.forEach(account -> byKey.put(account.resourceKey(), account));
        for (ResourceDemand demand : demands.values().stream().sorted(Comparator.comparing(ResourceDemand::resourceKey)).toList()) {
            ResourceAccount account = byKey.get(demand.resourceKey());
            if (account == null) throw new ConflictException("REPRICE_REQUIRED", "resource is unavailable: " + demand.resourceKey());
            if (account.type() != demand.type() || !account.currency().equals(demand.currency())) {
                throw new ConflictException("RESOURCE_SEMANTICS_MISMATCH", demand.resourceKey());
            }
            long expectedEpoch = request.expectedFencingEpochs().get(demand.resourceKey());
            ResourceAccount next = account.reserve(demand.amount(), expectedEpoch);
            saveAccount(tenantId, next);
            byKey.put(demand.resourceKey(), next);
        }
        String applicationId = UUID.randomUUID().toString();
        Instant now = clock.instant();
        try {
            jdbc.update("insert into mk_promotion_application(tenant_id,application_id,quote_id,decision_request_id,order_id,organization_id,shop_ids_json,cart_digest,token_digest,generation_no,state_name,total_discount,currency_code,expires_at,created_at,updated_at,expiry_next_attempt_at) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    tenantId, applicationId, claims.quoteId(), claims.decisionRequestId(), request.orderId(),
                    claims.organizationId(), json(new java.util.TreeSet<>(claims.shopIds())),
                    request.cartDigest(), Digests.sha256Hex(request.offerToken()), claims.generation(),
                    ApplicationState.RESERVED.name(), claims.totalDiscountMinorUnits(), claims.offerLines().getFirst().currency(),
                    format(claims.expiresAt()), format(now), format(now), format(now));
        } catch (DuplicateKeyException replay) {
            throw new ConflictException("OFFER_TOKEN_REPLAYED", "offer token quote was already reserved");
        }
        for (ResourceDemand demand : demands.values()) {
            jdbc.update("insert into mk_reservation_item(tenant_id,application_id,resource_key,resource_type,currency_code,original_amount,reserved_amount,consumed_amount,refunded_amount,released_amount,reservation_epoch) values(?,?,?,?,?,?,?,?,?,?,?)",
                    tenantId, applicationId, demand.resourceKey(), demand.type().name(), demand.currency(), demand.amount(),
                    demand.amount(), 0, 0, 0, byKey.get(demand.resourceKey()).fencingEpoch());
            ledger(tenantId, applicationId, request.orderId(), demand.resourceKey(), "RESERVE",
                    "AVAILABLE", "RESERVED", demand.amount(), byKey.get(demand.resourceKey()).version(), null, now);
        }
        outbox(tenantId, applicationId, "PromotionReserved", now);
        return application(tenantId, applicationId);
    }

    @Transactional
    public ApplicationView confirm(String applicationId, String commandId, SettlementRequest request) {
        return transition(applicationId, commandId, "CONFIRM", ApplicationState.RESERVED, ApplicationState.CONFIRMED,
                Operation.CONFIRM, Map.of(), request.expectedFencingEpochs());
    }

    @Transactional
    public ApplicationView cancel(String applicationId, String commandId, SettlementRequest request) {
        return transition(applicationId, commandId, "CANCEL", ApplicationState.RESERVED, ApplicationState.CANCELLED,
                Operation.RELEASE, Map.of(), request.expectedFencingEpochs());
    }

    @Transactional
    public ApplicationView refund(String applicationId, String commandId, RefundRequest request) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("promotion:refund");
        String payloadHash = Digests.sha256Hex("REFUND|" + applicationId + '|' + json(request));
        return command(scope.tenantId().value(), commandId, payloadHash, ApplicationView.class,
                () -> refundNow(scope, applicationId, request.resourceAmounts(), request.expectedFencingEpochs(), false));
    }

    @Transactional
    public ApplicationView reverse(String applicationId, String commandId, SettlementRequest request) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("promotion:reverse");
        String payloadHash = Digests.sha256Hex("REVERSE|" + applicationId + '|' + request.expectedFencingEpochs());
        return command(scope.tenantId().value(), commandId, payloadHash, ApplicationView.class,
                () -> refundNow(scope, applicationId, Map.of(), request.expectedFencingEpochs(), true));
    }

    public ExpirationResult expireReservations(int limit) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("promotion:expire");
        if (limit < 1 || limit > 1_000) throw new IllegalArgumentException("expiry limit must be in [1,1000]");
        Instant now = clock.instant();
        List<ExpiredApplication> candidates = jdbc.query("select tenant_id,application_id from mk_promotion_application where tenant_id=? and state_name='RESERVED' and expires_at<=? and expiry_next_attempt_at<=? order by expires_at limit ?",
                (rs, rowNum) -> new ExpiredApplication(rs.getString(1), rs.getString(2)),
                scope.tenantId().value(), format(now), format(now), limit);
        return expireCandidates(candidates, scope, now);
    }

    /** Each candidate is fenced and committed independently, so one poison row cannot roll back a batch. */
    public ExpirationResult expireDueReservations(int limit) {
        if (limit < 1 || limit > 1_000) throw new IllegalArgumentException("expiry limit must be in [1,1000]");
        Instant now = clock.instant();
        List<ExpiredApplication> candidates = jdbc.query("select tenant_id,application_id from mk_promotion_application where state_name='RESERVED' and expires_at<=? and expiry_next_attempt_at<=? order by expires_at limit ?",
                (rs, rowNum) -> new ExpiredApplication(rs.getString(1), rs.getString(2)),
                format(now), format(now), limit);
        return expireCandidates(candidates, null, now);
    }

    private ExpirationResult expireCandidates(List<ExpiredApplication> candidates, TenantScope scope, Instant now) {
        List<String> completed = new ArrayList<>();
        for (ExpiredApplication candidate : candidates) {
            try {
                Boolean changed = isolatedTransactions.execute(ignored -> expireCandidate(candidate, scope, now));
                if (Boolean.TRUE.equals(changed)) completed.add(candidate.applicationId());
            } catch (RuntimeException poison) {
                isolatedTransactions.executeWithoutResult(ignored -> recordExpirationFailure(candidate, poison, now));
            }
        }
        return new ExpirationResult(completed.size(), completed, now);
    }

    private boolean expireCandidate(ExpiredApplication candidate, TenantScope scope, Instant now) {
        StoredApplication application = lockApplication(candidate.tenantId(), candidate.applicationId());
        if (scope != null) requireApplicationScope(scope, application);
        if (application.state() != ApplicationState.RESERVED || application.expiresAt().isAfter(now)) return false;
        List<ReservationItem> items = lockItems(candidate.tenantId(), candidate.applicationId());
        apply(candidate.tenantId(), application, items, Operation.RELEASE, Map.of(), null);
        int updated = jdbc.update("update mk_promotion_application set state_name='EXPIRED',updated_at=? where tenant_id=? and application_id=? and state_name='RESERVED'",
                format(now), candidate.tenantId(), candidate.applicationId());
        if (updated != 1) return false;
        outbox(candidate.tenantId(), candidate.applicationId(), "PromotionExpired", now);
        return true;
    }

    private void recordExpirationFailure(ExpiredApplication candidate, RuntimeException failure, Instant now) {
        Integer attempts = jdbc.query("select expiry_attempts from mk_promotion_application where tenant_id=? and application_id=? for update",
                rs -> rs.next() ? rs.getInt(1) : null, candidate.tenantId(), candidate.applicationId());
        if (attempts == null) return;
        int nextAttempts = Math.addExact(attempts, 1);
        long delaySeconds = Math.min(300, 1L << Math.min(8, nextAttempts - 1));
        String error = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        if (error.length() > 1_000) error = error.substring(0, 1_000);
        jdbc.update("update mk_promotion_application set expiry_attempts=?,expiry_next_attempt_at=?,expiry_last_error=? where tenant_id=? and application_id=? and state_name='RESERVED'",
                nextAttempts, format(now.plusSeconds(delaySeconds)), error,
                candidate.tenantId(), candidate.applicationId());
    }

    private ApplicationView transition(String applicationId, String commandId, String semantic,
            ApplicationState expected, ApplicationState target, Operation operation, Map<String, Long> amounts,
            Map<String, Long> expectedFencingEpochs) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("promotion:" + semantic.toLowerCase(java.util.Locale.ROOT));
        String hash = Digests.sha256Hex(semantic + '|' + applicationId + '|' + amounts + '|'
                + expectedFencingEpochs);
        return command(scope.tenantId().value(), commandId, hash, ApplicationView.class, () -> {
            StoredApplication application = lockApplication(scope.tenantId().value(), applicationId);
            requireApplicationScope(scope, application);
            if (application.state() != expected) throw new ConflictException("APPLICATION_STATE_CONFLICT", expected.name());
            if (operation == Operation.CONFIRM && !application.expiresAt().isAfter(clock.instant())) {
                throw new ConflictException("OFFER_RESERVATION_EXPIRED", "expired reservation cannot be confirmed");
            }
            List<ReservationItem> items = lockItems(scope.tenantId().value(), applicationId);
            apply(scope.tenantId().value(), application, items, operation, amounts, expectedFencingEpochs);
            Instant now = clock.instant();
            jdbc.update("update mk_promotion_application set state_name=?,updated_at=? where tenant_id=? and application_id=?",
                    target.name(), format(now), scope.tenantId().value(), applicationId);
            outbox(scope.tenantId().value(), applicationId, "Promotion" + target.name(), now);
            return application(scope.tenantId().value(), applicationId);
        });
    }

    private ApplicationView refundNow(TenantScope scope, String applicationId, Map<String, Long> requested,
            Map<String, Long> expectedFencingEpochs, boolean reverse) {
        String tenantId = scope.tenantId().value();
        StoredApplication application = lockApplication(tenantId, applicationId);
        requireApplicationScope(scope, application);
        if (application.state() != ApplicationState.CONFIRMED
                && application.state() != ApplicationState.PARTIALLY_REFUNDED) {
            throw new ConflictException("APPLICATION_STATE_CONFLICT", "confirmed application required");
        }
        List<ReservationItem> items = lockItems(tenantId, applicationId);
        Set<String> knownResources = items.stream().map(ReservationItem::resourceKey)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!reverse && requested.keySet().stream().anyMatch(key -> !knownResources.contains(key))) {
            throw new ConflictException("REFUND_RESOURCE_UNKNOWN", "refund contains a resource outside the application");
        }
        Map<String, Long> amounts = new LinkedHashMap<>();
        for (ReservationItem item : items) {
            long remaining = item.consumedAmount();
            long amount = reverse || requested.isEmpty() ? remaining : requested.getOrDefault(item.resourceKey(), 0L);
            if (amount < 0 || amount > remaining) throw new ConflictException("REFUND_EXCEEDS_ORIGINAL", item.resourceKey());
            if (amount > 0) amounts.put(item.resourceKey(), amount);
        }
        if (amounts.isEmpty()) throw new ConflictException("REFUND_EMPTY", "refund has no resource amount");
        apply(tenantId, application, items, Operation.REFUND, amounts, expectedFencingEpochs);
        boolean fullyRefunded = items.stream().allMatch(item ->
                amounts.getOrDefault(item.resourceKey(), 0L) == item.consumedAmount());
        ApplicationState next = reverse ? ApplicationState.REVERSED
                : fullyRefunded ? ApplicationState.REFUNDED : ApplicationState.PARTIALLY_REFUNDED;
        Instant now = clock.instant();
        jdbc.update("update mk_promotion_application set state_name=?,updated_at=? where tenant_id=? and application_id=?",
                next.name(), format(now), tenantId, applicationId);
        outbox(tenantId, applicationId, "Promotion" + next.name(), now);
        return application(tenantId, applicationId);
    }

    private void apply(String tenantId, StoredApplication application, List<ReservationItem> items,
            Operation operation, Map<String, Long> requested, Map<String, Long> expectedFencingEpochs) {
        List<String> keys = items.stream().map(ReservationItem::resourceKey).sorted().toList();
        if (expectedFencingEpochs != null && !expectedFencingEpochs.keySet().equals(Set.copyOf(keys))) {
            throw new ConflictException("FENCING_EPOCHS_INCOMPLETE",
                    "every settled resource requires exactly one current fencing epoch");
        }
        Map<String, ResourceAccount> accounts = new LinkedHashMap<>();
        lockAccounts(tenantId, keys).forEach(account -> accounts.put(account.resourceKey(), account));
        Instant now = clock.instant();
        for (ReservationItem item : items.stream().sorted(Comparator.comparing(ReservationItem::resourceKey)).toList()) {
            long amount = requested.isEmpty()
                    ? (operation == Operation.CONFIRM || operation == Operation.RELEASE ? item.reservedAmount() : item.consumedAmount())
                    : requested.getOrDefault(item.resourceKey(), 0L);
            if (amount == 0) continue;
            ResourceAccount account = accounts.get(item.resourceKey());
            if (account == null) throw new ConflictException("RESOURCE_UNAVAILABLE", item.resourceKey());
            long writerEpoch = expectedFencingEpochs == null
                    ? account.fencingEpoch() : expectedFencingEpochs.get(item.resourceKey());
            ResourceAccount next = switch (operation) {
                case CONFIRM -> account.confirm(amount, writerEpoch);
                case RELEASE -> account.release(amount, writerEpoch);
                case REFUND -> account.refund(amount, writerEpoch);
            };
            saveAccount(tenantId, next);
            String from = operation == Operation.CONFIRM || operation == Operation.RELEASE ? "RESERVED" : "CONSUMED";
            String to = operation == Operation.CONFIRM ? "CONSUMED" : "AVAILABLE";
            ledger(tenantId, application.applicationId(), application.orderId(), item.resourceKey(),
                    operation.name(), from, to, amount, next.version(),
                    operation == Operation.REFUND ? application.applicationId() : null, now);
            switch (operation) {
                case CONFIRM -> jdbc.update("update mk_reservation_item set reserved_amount=reserved_amount-?,consumed_amount=consumed_amount+? where tenant_id=? and application_id=? and resource_key=?",
                        amount, amount, tenantId, application.applicationId(), item.resourceKey());
                case RELEASE -> jdbc.update("update mk_reservation_item set reserved_amount=reserved_amount-?,released_amount=released_amount+? where tenant_id=? and application_id=? and resource_key=?",
                        amount, amount, tenantId, application.applicationId(), item.resourceKey());
                case REFUND -> jdbc.update("update mk_reservation_item set consumed_amount=consumed_amount-?,refunded_amount=refunded_amount+? where tenant_id=? and application_id=? and resource_key=?",
                        amount, amount, tenantId, application.applicationId(), item.resourceKey());
            }
        }
    }

    @Transactional(readOnly = true)
    public ReconciliationReport reconcile() {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("funding:reconcile");
        String tenantId = scope.tenantId().value();
        List<String> violations = new ArrayList<>();
        List<ResourceSnapshot> resources = jdbc.query("select resource_key,authorized_amount,available_amount,reserved_amount,consumed_amount,returned_amount from mk_resource_account where tenant_id=?",
                (rs, rowNum) -> new ResourceSnapshot(rs.getString(1), rs.getLong(2), rs.getLong(3),
                        rs.getLong(4), rs.getLong(5), rs.getLong(6)), tenantId);
        Map<String, ReplayBalance> balances = new LinkedHashMap<>();
        resources.forEach(resource -> balances.put(resource.resourceKey(), new ReplayBalance(resource.authorized())));
        Map<ApplicationResourceKey, ReservationReplay> reservationReplay = new LinkedHashMap<>();
        List<LedgerEntry> ledger = jdbc.query("select application_id,resource_key,operation_name,debit_bucket,credit_bucket,amount_value,account_version from mk_funding_ledger where tenant_id=? order by resource_key,account_version,ledger_id",
                (rs, rowNum) -> new LedgerEntry(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getLong(6), rs.getLong(7)), tenantId);
        long ledgerMovement = 0;
        for (LedgerEntry entry : ledger) {
            try {
                ledgerMovement = Math.addExact(ledgerMovement, entry.amount());
                ReplayBalance balance = balances.get(entry.resourceKey());
                if (balance == null) {
                    violations.add(entry.resourceKey() + ":LEDGER_RESOURCE_MISSING");
                    continue;
                }
                balance.apply(entry, violations);
                if (!"GRANT".equals(entry.operation())) {
                    reservationReplay.computeIfAbsent(
                                    new ApplicationResourceKey(entry.applicationId(), entry.resourceKey()),
                                    ignored -> new ReservationReplay())
                            .apply(entry, violations);
                }
            } catch (ArithmeticException overflow) {
                violations.add(entry.resourceKey() + ":LEDGER_ARITHMETIC_OVERFLOW");
            }
        }
        for (ResourceSnapshot resource : resources) {
            ReplayBalance replay = balances.get(resource.resourceKey());
            if (replay.available != resource.available() || replay.reserved != resource.reserved()
                    || replay.consumed != resource.consumed() || replay.returned != resource.returned()) {
                violations.add(resource.resourceKey() + ":ACCOUNT_LEDGER_MISMATCH");
            }
        }
        List<ReconcileItem> items = jdbc.query("select application_id,resource_key,original_amount,reserved_amount,consumed_amount,refunded_amount,released_amount,reservation_epoch from mk_reservation_item where tenant_id=?",
                (rs, rowNum) -> new ReconcileItem(rs.getString(1), rs.getString(2), rs.getLong(3),
                        rs.getLong(4), rs.getLong(5), rs.getLong(6), rs.getLong(7), rs.getLong(8)), tenantId);
        Map<String, List<ReconcileItem>> itemsByApplication = new LinkedHashMap<>();
        Set<ApplicationResourceKey> storedItemKeys = new java.util.HashSet<>();
        for (ReconcileItem item : items) {
            storedItemKeys.add(new ApplicationResourceKey(item.applicationId(), item.resourceKey()));
            itemsByApplication.computeIfAbsent(item.applicationId(), ignored -> new ArrayList<>()).add(item);
            ReservationReplay replay = reservationReplay.get(
                    new ApplicationResourceKey(item.applicationId(), item.resourceKey()));
            if (replay == null || !replay.matches(item)) {
                violations.add(item.applicationId() + ':' + item.resourceKey() + ":RESERVATION_LEDGER_MISMATCH");
            }
            if (item.reservationEpoch() < 1) {
                violations.add(item.applicationId() + ':' + item.resourceKey() + ":RESERVATION_EPOCH_INVALID");
            }
        }
        reservationReplay.keySet().stream().filter(key -> !storedItemKeys.contains(key)).forEach(key ->
                violations.add(key.applicationId() + ':' + key.resourceKey() + ":RESERVATION_ITEM_MISSING"));
        Map<String, Set<String>> outboxTypes = new LinkedHashMap<>();
        jdbc.query("select aggregate_id,event_type from mk_benefit_outbox where tenant_id=?", rs -> {
            outboxTypes.computeIfAbsent(rs.getString(1), ignored -> new java.util.HashSet<>()).add(rs.getString(2));
        }, tenantId);
        jdbc.query("select application_id,state_name from mk_promotion_application where tenant_id=?", rs -> {
            String applicationId = rs.getString(1);
            ApplicationState state = ApplicationState.valueOf(rs.getString(2));
            List<ReconcileItem> applicationItems = itemsByApplication.getOrDefault(applicationId, List.of());
            if (applicationItems.isEmpty() || !stateMatches(state, applicationItems)) {
                violations.add(applicationId + ":APPLICATION_RESERVATION_MISMATCH");
            }
            Set<String> events = outboxTypes.getOrDefault(applicationId, Set.of());
            if (!events.contains("PromotionReserved") || !events.contains(expectedStateEvent(state))) {
                violations.add(applicationId + ":OUTBOX_INCOMPLETE");
            }
        }, tenantId);
        violations.sort(String::compareTo);
        return new ReconciliationReport(violations.isEmpty(), violations, ledgerMovement, clock.instant());
    }

    private static boolean stateMatches(ApplicationState state, List<ReconcileItem> items) {
        return switch (state) {
            case RESERVED -> items.stream().allMatch(item -> item.reserved() == item.original());
            case CONFIRMED -> items.stream().allMatch(item -> item.reserved() == 0 && item.consumed() == item.original());
            case CANCELLED, EXPIRED -> items.stream().allMatch(item -> item.reserved() == 0
                    && item.consumed() == 0 && item.released() == item.original());
            case PARTIALLY_REFUNDED -> items.stream().allMatch(item -> item.reserved() == 0)
                    && items.stream().anyMatch(item -> item.refunded() > 0)
                    && items.stream().anyMatch(item -> item.consumed() > 0);
            case REFUNDED, REVERSED -> items.stream().allMatch(item -> item.reserved() == 0
                    && item.consumed() == 0 && item.refunded() == item.original());
        };
    }

    private static String expectedStateEvent(ApplicationState state) {
        return state == ApplicationState.RESERVED ? "PromotionReserved"
                : state == ApplicationState.EXPIRED ? "PromotionExpired" : "Promotion" + state.name();
    }

    private Map<String, ResourceDemand> demands(OfferTokenClaims claims) {
        Map<String, ResourceDemand> demands = new LinkedHashMap<>();
        for (OfferLineClaim offer : claims.offerLines()) {
            merge(demands, new ResourceDemand("INVENTORY:" + offer.benefitDefinitionVersion(),
                    ResourceAccount.Type.INVENTORY, "UNIT", offer.quantity()));
            for (FundingShareClaim share : offer.fundingShares()) {
                if (share.minorUnits() > 0) {
                    merge(demands, new ResourceDemand("BUDGET:" + share.funderType() + ':' + share.funderId() + ':'
                            + share.currency(), ResourceAccount.Type.BUDGET, share.currency(), share.minorUnits()));
                }
            }
        }
        return demands;
    }

    private static void merge(Map<String, ResourceDemand> demands, ResourceDemand demand) {
        demands.merge(demand.resourceKey(), demand, (left, right) -> new ResourceDemand(left.resourceKey(),
                left.type(), left.currency(), Math.addExact(left.amount(), right.amount())));
    }

    private List<ResourceAccount> lockAccounts(String tenantId, List<String> keys) {
        List<ResourceAccount> accounts = new ArrayList<>();
        for (String key : keys) {
            accounts.addAll(jdbc.query("select resource_key,resource_type,currency_code,authorized_amount,available_amount,reserved_amount,consumed_amount,returned_amount,fencing_epoch,version_no,state_name from mk_resource_account where tenant_id=? and resource_key=? for update",
                    (rs, rowNum) -> new ResourceAccount(rs.getString(1), ResourceAccount.Type.valueOf(rs.getString(2)),
                            rs.getString(3), rs.getLong(4), rs.getLong(5), rs.getLong(6), rs.getLong(7),
                            rs.getLong(8), rs.getLong(9), rs.getLong(10), ResourceAccount.State.valueOf(rs.getString(11))),
                    tenantId, key));
        }
        return accounts;
    }

    private ResourceAccount resourceAccount(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ResourceAccount(rs.getString(1), ResourceAccount.Type.valueOf(rs.getString(2)),
                rs.getString(3), rs.getLong(4), rs.getLong(5), rs.getLong(6), rs.getLong(7),
                rs.getLong(8), rs.getLong(9), rs.getLong(10), ResourceAccount.State.valueOf(rs.getString(11)));
    }

    private BenefitView benefitView(java.sql.ResultSet rs) throws java.sql.SQLException {
        @SuppressWarnings("unchecked")
        Map<String, Object> policy = read(rs.getString(7), Map.class);
        return new BenefitView(rs.getString(1), rs.getLong(2), rs.getString(3),
                BenefitStatus.valueOf(rs.getString(4)), rs.getString(5), rs.getString(6), policy, rs.getString(8),
                Instant.parse(rs.getString(9)));
    }

    private void saveAccount(String tenantId, ResourceAccount account) {
        int count = jdbc.update("update mk_resource_account set available_amount=?,reserved_amount=?,consumed_amount=?,returned_amount=?,version_no=?,updated_at=? where tenant_id=? and resource_key=? and version_no=?",
                account.available(), account.reserved(), account.consumed(), account.returned(), account.version(),
                format(clock.instant()), tenantId, account.resourceKey(), account.version() - 1);
        if (count != 1) throw new ConflictException("RESOURCE_CONCURRENT_MODIFICATION", account.resourceKey());
    }

    private StoredApplication lockApplication(String tenantId, String applicationId) {
        List<StoredApplication> applications = jdbc.query("select application_id,order_id,organization_id,shop_ids_json,state_name,expires_at from mk_promotion_application where tenant_id=? and application_id=? for update",
                (rs, rowNum) -> new StoredApplication(rs.getString(1), rs.getString(2),
                        rs.getString(3), stringSet(rs.getString(4)), ApplicationState.valueOf(rs.getString(5)),
                        Instant.parse(rs.getString(6))), tenantId, applicationId);
        if (applications.isEmpty()) throw new NotFoundException("APPLICATION_NOT_FOUND", "promotion application not found");
        return applications.getFirst();
    }

    private static void requireApplicationScope(TenantScope scope, StoredApplication application) {
        scope.requireOrganization(application.organizationId());
        application.shopIds().forEach(scope::requireShop);
    }

    private Set<String> stringSet(String value) {
        try {
            String[] values = mapper.readValue(value, String[].class);
            return Set.copyOf(java.util.Arrays.asList(values));
        } catch (JacksonException failure) {
            throw new IllegalStateException("stored application scope is invalid", failure);
        }
    }

    private List<ReservationItem> lockItems(String tenantId, String applicationId) {
        return jdbc.query("select resource_key,original_amount,reserved_amount,consumed_amount,refunded_amount,released_amount,reservation_epoch from mk_reservation_item where tenant_id=? and application_id=? order by resource_key for update",
                (rs, rowNum) -> new ReservationItem(rs.getString(1), rs.getLong(2), rs.getLong(3),
                        rs.getLong(4), rs.getLong(5), rs.getLong(6), rs.getLong(7)), tenantId, applicationId);
    }

    private ApplicationView application(String tenantId, String applicationId) {
        List<ApplicationView> applications = jdbc.query("select quote_id,order_id,cart_digest,generation_no,state_name,total_discount,currency_code,expires_at,created_at,updated_at from mk_promotion_application where tenant_id=? and application_id=?",
                (rs, rowNum) -> new ApplicationView(applicationId, rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getLong(4), ApplicationState.valueOf(rs.getString(5)), rs.getLong(6), rs.getString(7),
                        items(tenantId, applicationId), Instant.parse(rs.getString(8)), Instant.parse(rs.getString(9)),
                        Instant.parse(rs.getString(10))),
                tenantId, applicationId);
        if (applications.isEmpty()) throw new NotFoundException("APPLICATION_NOT_FOUND", "promotion application not found");
        return applications.getFirst();
    }

    private List<ItemView> items(String tenantId, String applicationId) {
        return jdbc.query("select resource_key,resource_type,currency_code,original_amount,reserved_amount,consumed_amount,refunded_amount,released_amount,reservation_epoch from mk_reservation_item where tenant_id=? and application_id=? order by resource_key",
                (rs, rowNum) -> new ItemView(rs.getString(1), ResourceAccount.Type.valueOf(rs.getString(2)),
                        rs.getString(3), rs.getLong(4), rs.getLong(5), rs.getLong(6), rs.getLong(7),
                        rs.getLong(8), rs.getLong(9)),
                tenantId, applicationId);
    }

    private <T> T command(String tenantId, String commandId, String payloadHash, Class<T> type, Supplier<T> action) {
        if (commandId == null || !commandId.matches("[a-zA-Z0-9_.:-]{8,128}")) {
            throw new IllegalArgumentException("command id is invalid");
        }
        boolean owner = false;
        try {
            jdbc.update("insert into mk_command_dedup(tenant_id,command_id,payload_hash,state_name,created_at,expires_at) values(?,?,?,?,?,?)",
                    tenantId, commandId, payloadHash, "PROCESSING", format(clock.instant()),
                    format(clock.instant().plusSeconds(604_800)));
            owner = true;
        } catch (DuplicateKeyException duplicate) {
            // Existing command row is locked/read below; committed original response is returned byte-for-byte.
        }
        List<StoredCommand> rows = jdbc.query("select payload_hash,state_name,response_json from mk_command_dedup where tenant_id=? and command_id=? for update",
                (rs, rowNum) -> new StoredCommand(rs.getString(1), rs.getString(2), rs.getString(3)), tenantId, commandId);
        StoredCommand stored = rows.getFirst();
        if (!stored.payloadHash().equals(payloadHash)) {
            throw new ConflictException("IDEMPOTENCY_PAYLOAD_CONFLICT", "command id was used with another payload");
        }
        if (!owner) {
            if (!"COMPLETED".equals(stored.state()) || stored.responseJson() == null) {
                throw new ConflictException("COMMAND_IN_PROGRESS", "original command is still in progress");
            }
            return read(stored.responseJson(), type);
        }
        T response = action.get();
        jdbc.update("update mk_command_dedup set state_name='COMPLETED',response_json=? where tenant_id=? and command_id=?",
                json(response), tenantId, commandId);
        return response;
    }

    private void ledger(String tenantId, String applicationId, String orderId, String resourceKey,
            String operation, String debitBucket, String creditBucket, long amount, long accountVersion,
            String correctionOf, Instant now) {
        jdbc.update("insert into mk_funding_ledger(tenant_id,ledger_id,application_id,order_id,resource_key,operation_name,debit_bucket,credit_bucket,amount_value,account_version,correction_of,occurred_at) values(?,?,?,?,?,?,?,?,?,?,?,?)",
                tenantId, UUID.randomUUID().toString(), applicationId, orderId, resourceKey, operation,
                debitBucket, creditBucket, amount, accountVersion, correctionOf, format(now));
    }

    private void outbox(String tenantId, String applicationId, String type, Instant now) {
        long sequence = nextOutboxSequence(tenantId, applicationId, now);
        String eventId = UUID.randomUUID().toString();
        jdbc.update("insert into mk_benefit_outbox(tenant_id,event_id,aggregate_id,event_type,destination_topic,partition_key,stream_sequence,payload_json,next_attempt_at,created_at) values(?,?,?,?,?,?,?,?,?,?)",
                tenantId, eventId, applicationId, type, "mk.benefit.event.v1",
                tenantId + ':' + applicationId, sequence,
                json(Map.of("eventId", eventId, "eventType", type, "tenantId", tenantId,
                        "aggregateId", applicationId, "occurredAt", format(now))),
                format(now), format(now));
    }

    private long nextOutboxSequence(String tenantId, String aggregateId, Instant now) {
        int updated = jdbc.update("update mk_benefit_outbox_position set last_sequence=last_sequence+1,updated_at=? where tenant_id=? and aggregate_id=?",
                format(now), tenantId, aggregateId);
        if (updated == 0) {
            try {
                jdbc.update("insert into mk_benefit_outbox_position(tenant_id,aggregate_id,last_sequence,updated_at) values(?,?,?,?)",
                        tenantId, aggregateId, 1, format(now));
            } catch (DuplicateKeyException race) {
                jdbc.update("update mk_benefit_outbox_position set last_sequence=last_sequence+1,updated_at=? where tenant_id=? and aggregate_id=?",
                        format(now), tenantId, aggregateId);
            }
        }
        Long value = jdbc.query("select last_sequence from mk_benefit_outbox_position where tenant_id=? and aggregate_id=?",
                rs -> rs.next() ? rs.getLong(1) : null, tenantId, aggregateId);
        if (value == null) throw new IllegalStateException("benefit outbox sequence allocation failed");
        return value;
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JacksonException failure) { throw new IllegalArgumentException("command cannot be serialized", failure); }
    }
    private <T> T read(String value, Class<T> type) {
        try { return mapper.readValue(value, type); }
        catch (JacksonException failure) { throw new IllegalStateException("stored command response is invalid", failure); }
    }

    private enum Operation { CONFIRM, RELEASE, REFUND }
    public enum ApplicationState { RESERVED, CONFIRMED, CANCELLED, EXPIRED, PARTIALLY_REFUNDED, REFUNDED, REVERSED }
    public enum BenefitStatus { DRAFT, ACTIVE, RETIRED }
    private record ResourceSnapshot(String resourceKey, long authorized, long available, long reserved,
            long consumed, long returned) { }
    private record LedgerEntry(String applicationId, String resourceKey, String operation,
            String debitBucket, String creditBucket, long amount, long accountVersion) { }
    private record ApplicationResourceKey(String applicationId, String resourceKey) { }
    private record ReconcileItem(String applicationId, String resourceKey, long original, long reserved,
            long consumed, long refunded, long released, long reservationEpoch) { }
    private static final class ReplayBalance {
        private long available;
        private long reserved;
        private long consumed;
        private long returned;

        private ReplayBalance(long authorized) { this.available = authorized; }

        private void apply(LedgerEntry entry, List<String> violations) {
            if (entry.amount() <= 0) {
                violations.add(entry.resourceKey() + ":LEDGER_AMOUNT_INVALID");
                return;
            }
            String shape = entry.operation() + '|' + entry.debitBucket() + '|' + entry.creditBucket();
            switch (shape) {
                case "RESERVE|AVAILABLE|RESERVED" -> {
                    if (available < entry.amount()) { invalid(entry, violations); return; }
                    available -= entry.amount();
                    reserved = Math.addExact(reserved, entry.amount());
                }
                case "CONFIRM|RESERVED|CONSUMED" -> {
                    if (reserved < entry.amount()) { invalid(entry, violations); return; }
                    reserved -= entry.amount();
                    consumed = Math.addExact(consumed, entry.amount());
                }
                case "RELEASE|RESERVED|AVAILABLE" -> {
                    if (reserved < entry.amount()) { invalid(entry, violations); return; }
                    reserved -= entry.amount();
                    available = Math.addExact(available, entry.amount());
                }
                case "REFUND|CONSUMED|AVAILABLE" -> {
                    if (consumed < entry.amount()) { invalid(entry, violations); return; }
                    consumed -= entry.amount();
                    available = Math.addExact(available, entry.amount());
                    returned = Math.addExact(returned, entry.amount());
                }
                case "GRANT|AVAILABLE|CONSUMED" -> {
                    if (available < entry.amount()) { invalid(entry, violations); return; }
                    available -= entry.amount();
                    consumed = Math.addExact(consumed, entry.amount());
                }
                default -> violations.add(entry.resourceKey() + ":LEDGER_SHAPE_INVALID");
            }
        }

        private static void invalid(LedgerEntry entry, List<String> violations) {
            violations.add(entry.resourceKey() + ":LEDGER_BUCKET_UNDERFLOW");
        }
    }
    private static final class ReservationReplay {
        private long original;
        private long reserved;
        private long consumed;
        private long refunded;
        private long released;

        private void apply(LedgerEntry entry, List<String> violations) {
            long amount = entry.amount();
            if (amount <= 0) return;
            switch (entry.operation()) {
                case "RESERVE" -> {
                    original = Math.addExact(original, amount);
                    reserved = Math.addExact(reserved, amount);
                }
                case "CONFIRM" -> {
                    if (reserved < amount) { invalid(entry, violations); return; }
                    reserved -= amount;
                    consumed = Math.addExact(consumed, amount);
                }
                case "RELEASE" -> {
                    if (reserved < amount) { invalid(entry, violations); return; }
                    reserved -= amount;
                    released = Math.addExact(released, amount);
                }
                case "REFUND" -> {
                    if (consumed < amount) { invalid(entry, violations); return; }
                    consumed -= amount;
                    refunded = Math.addExact(refunded, amount);
                }
                default -> { }
            }
        }

        private boolean matches(ReconcileItem item) {
            return original == item.original() && reserved == item.reserved() && consumed == item.consumed()
                    && refunded == item.refunded() && released == item.released();
        }

        private static void invalid(LedgerEntry entry, List<String> violations) {
            violations.add(entry.applicationId() + ':' + entry.resourceKey() + ":RESERVATION_LEDGER_UNDERFLOW");
        }
    }
    private record ResourceDemand(String resourceKey, ResourceAccount.Type type, String currency, long amount) { }
    private record StoredApplication(String applicationId, String orderId, String organizationId,
            Set<String> shopIds, ApplicationState state,
            Instant expiresAt) { }
    private record ExpiredApplication(String tenantId, String applicationId) { }
    private record ReservationItem(String resourceKey, long originalAmount, long reservedAmount,
            long consumedAmount, long refundedAmount, long releasedAmount, long reservationEpoch) { }
    private record StoredCommand(String payloadHash, String state, String responseJson) { }
    public record CreateAccountRequest(String resourceKey, ResourceAccount.Type type, String currency,
            long authorized, long fencingEpoch) { }
    public record BenefitRequest(String name, BenefitStatus status, String resourceKey, String benefitSkuId,
            Map<String, Object> policy) {
        public BenefitRequest {
            if (name == null || name.isBlank() || name.length() > 256) {
                throw new IllegalArgumentException("benefit name is invalid");
            }
            status = status == null ? BenefitStatus.DRAFT : status;
            resourceKey = resourceKey == null ? "" : resourceKey;
            if (resourceKey.length() > 256) throw new IllegalArgumentException("benefit resource key is invalid");
            benefitSkuId = benefitSkuId == null || benefitSkuId.isBlank() ? null : benefitSkuId.trim();
            if (benefitSkuId != null && benefitSkuId.length() > 128) {
                throw new IllegalArgumentException("benefit SKU id is invalid");
            }
            policy = Map.copyOf(policy == null ? Map.of() : policy);
        }
    }
    public record BenefitView(String benefitId, long version, String name, BenefitStatus status,
            String resourceKey, String benefitSkuId, Map<String, Object> policy, String createdBy, Instant createdAt) {
        public BenefitView { policy = Map.copyOf(policy); }
    }
    public record FencingLeaseRequest(long expectedEpoch, ResourceAccount.State state) {
        public FencingLeaseRequest {
            if (expectedEpoch < 1 || state == null || state == ResourceAccount.State.CLOSED) {
                throw new IllegalArgumentException("fencing lease transition is invalid");
            }
        }
    }
    public record AccountView(String resourceKey, ResourceAccount.Type type, String currency, long authorized,
            long available, long reserved, long consumed, long returned, long fencingEpoch, long version,
            ResourceAccount.State state) {
        static AccountView from(ResourceAccount account) {
            return new AccountView(account.resourceKey(), account.type(), account.currency(), account.authorized(),
                    account.available(), account.reserved(), account.consumed(), account.returned(),
                    account.fencingEpoch(), account.version(), account.state());
        }
    }
    public record ReserveRequest(String offerToken, String cartDigest, String orderId,
            Map<String, Long> expectedFencingEpochs) {
        public ReserveRequest {
            orderId = orderId == null ? "" : orderId;
            expectedFencingEpochs = sortedMap(expectedFencingEpochs);
        }
    }
    public record SettlementRequest(Map<String, Long> expectedFencingEpochs) {
        public SettlementRequest { expectedFencingEpochs = sortedMap(expectedFencingEpochs); }
    }
    public record RefundRequest(Map<String, Long> resourceAmounts, Map<String, Long> expectedFencingEpochs) {
        public RefundRequest {
            resourceAmounts = sortedMap(resourceAmounts);
            expectedFencingEpochs = sortedMap(expectedFencingEpochs);
        }
    }
    public record ItemView(String resourceKey, ResourceAccount.Type type, String currency, long originalAmount,
            long reservedAmount, long consumedAmount, long refundedAmount, long releasedAmount,
            long reservationEpoch) { }
    public record ApplicationView(String applicationId, String quoteId, String orderId, String cartDigest,
            long generation, ApplicationState state, long totalDiscount, String currency,
            List<ItemView> items, Instant expiresAt, Instant createdAt, Instant updatedAt) {
        public ApplicationView { items = List.copyOf(items); }
    }
    public record ReconciliationReport(boolean balanced, List<String> violations, long ledgerMovement,
            Instant checkedAt) {
        public ReconciliationReport { violations = List.copyOf(violations); }
    }
    public record ExpirationResult(int expiredCount, List<String> applicationIds, Instant completedAt) {
        public ExpirationResult { applicationIds = List.copyOf(applicationIds); }
    }
    public record JourneyGrantView(String commandId, String enrollmentId, String resourceKey,
            String benefitId, long quantity, long fencingEpoch, Instant grantedAt) { }

    private static Map<String, Long> sortedMap(Map<String, Long> source) {
        return Collections.unmodifiableMap(new TreeMap<>(source == null ? Map.of() : source));
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
}
