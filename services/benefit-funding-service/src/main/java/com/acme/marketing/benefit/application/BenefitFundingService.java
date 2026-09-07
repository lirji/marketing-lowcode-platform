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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class BenefitFundingService {
    private final BenefitFundingRepository repository;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final OfferTokenTrust tokenTrust;
    private final BenefitSkuCatalog benefitSkuCatalog;
    private final TransactionTemplate isolatedTransactions;
    private final int budgetBucketCount;

    /** 构造资金应用服务；数据库实现经持久化端口注入，应用层不绑定 MyBatis。 */
    public BenefitFundingService(BenefitFundingRepository repository, ObjectMapper mapper, Clock clock,
            OfferTokenTrust tokenTrust,
            BenefitSkuCatalog benefitSkuCatalog, PlatformTransactionManager transactionManager,
            @Value("${marketing.funding.budget-bucket-count:16}") int budgetBucketCount) {
        if (budgetBucketCount < 2 || budgetBucketCount > 64) {
            throw new IllegalArgumentException("budget bucket count must be in [2,64]");
        }
        this.repository = repository;
        this.mapper = mapper;
        this.clock = clock;
        this.tokenTrust = tokenTrust;
        this.benefitSkuCatalog = benefitSkuCatalog;
        this.isolatedTransactions = new TransactionTemplate(transactionManager);
        this.isolatedTransactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.budgetBucketCount = budgetBucketCount;
    }

    @Transactional
    public AccountView createAccount(CreateAccountRequest request) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("funding:account-write");
        ResourceAccount account = new ResourceAccount(request.resourceKey(), request.type(), request.currency(),
                request.authorized(), request.authorized(), 0, 0, 0, request.fencingEpoch(), 0,
                ResourceAccount.State.ACTIVE);
        repository.saveAccount(new BenefitFundingRepository.AccountWrite(scope.tenantId().value(),
                account.resourceKey(), account.type().name(), account.currency(), account.authorized(),
                account.available(), account.reserved(), account.consumed(), account.returned(),
                account.fencingEpoch(), account.version(), account.state().name(), format(clock.instant())));
        if (account.type() == ResourceAccount.Type.BUDGET) {
            createBudgetBuckets(scope.tenantId().value(), account);
        }
        return AccountView.from(account);
    }

    @Transactional(readOnly = true)
    public List<AccountView> accounts() {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("funding:account-read");
        List<ResourceAccount> roots = repository.findAccounts(scope.tenantId().value(), true);
        return roots.stream().map(account -> accountView(scope.tenantId().value(), account)).toList();
    }

    @Transactional(readOnly = true)
    public List<BenefitView> benefits() {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("benefit:read");
        return repository.findLatestBenefits(scope.tenantId().value()).stream()
                .map(this::benefitView).toList();
    }

    @Transactional(readOnly = true)
    public BenefitView benefit(String benefitId) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("benefit:read");
        return repository.findLatestBenefit(scope.tenantId().value(), benefitId)
                .map(this::benefitView)
                .orElseThrow(() -> new NotFoundException("BENEFIT_NOT_FOUND", "benefit not found"));
    }

    /** 发布门禁按不可变 definition version 校验，且每次直读权益中台，避免 ACTIVE 状态缓存窗口。 */
    @Transactional(readOnly = true)
    public ReleaseEligibility assertReleasable(Set<String> references) {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("release:write");
        if (references == null || references.isEmpty()) {
            throw new IllegalArgumentException("benefit definition references are required");
        }
        for (String reference : references) {
            BenefitVersion version = BenefitVersion.parse(reference);
            BenefitFundingRepository.ReleaseBenefitRow row = repository.findReleaseBenefit(
                    scope.tenantId().value(), version.benefitId(), version.version()).orElse(null);
            if (row == null) {
                throw new ConflictException("BENEFIT_RELEASE_NOT_FOUND",
                        "referenced BenefitDefinition version does not exist: " + reference);
            }
            ReleaseBenefit benefit = new ReleaseBenefit(row.status(), row.benefitSkuId(),
                    readMap(row.policyJson()));
            if (!BenefitStatus.ACTIVE.name().equals(benefit.status())) {
                throw new ConflictException("BENEFIT_RELEASE_NOT_ACTIVE",
                        "referenced BenefitDefinition is not ACTIVE: " + reference);
            }
            if (benefit.benefitSkuId() == null || benefit.benefitSkuId().isBlank()) {
                throw new ConflictException("BENEFIT_RELEASE_UNBOUND",
                        "referenced BenefitDefinition has no benefit SKU: " + reference);
            }
            BenefitSkuView sku = benefitSkuCatalog.requireActiveSku(
                    scope.tenantId().value(), benefit.benefitSkuId());
            requireMatchingBenefitType(benefit.policy(), sku);
        }
        return new ReleaseEligibility(true, Set.copyOf(references));
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
                    // 只让首次幂等命令执行绑定校验；成功回放不受后续模板暂停影响。
                    if (request.benefitSkuId() != null) {
                        BenefitSkuView sku = benefitSkuCatalog.requireActiveSku(
                                scope.tenantId().value(), request.benefitSkuId());
                        requireMatchingBenefitType(request.policy(), sku);
                    } else if (request.status() == BenefitStatus.ACTIVE) {
                        throw new ConflictException("SKU_NOT_ACTIVE",
                                "ACTIVE benefit must bind an ACTIVE benefit SKU");
                    }
                    return putBenefitNow(scope.tenantId().value(), scope.actorId(), benefitId, request);
                });
    }

    /** SKU 类型和营销策略类型必须完全一致，禁止在入库或发奖时静默改写语义。 */
    private static void requireMatchingBenefitType(Map<String, Object> policy, BenefitSkuView sku) {
        Object configured = policy.get("type");
        if (!(configured instanceof String type) || !sku.benefitType().equals(type)) {
            throw new ConflictException("BENEFIT_SKU_TYPE_MISMATCH",
                    "policy.type must exactly match benefit SKU type " + sku.benefitType());
        }
    }

    private BenefitView putBenefitNow(String tenantId, String actorId, String benefitId, BenefitRequest request) {
        repository.ensureBenefitHead(tenantId, benefitId);
        long current = repository.findBenefitHeadForUpdate(tenantId, benefitId)
                .orElseThrow(() -> new IllegalStateException("benefit version head was not created"));
        long version = Math.addExact(current, 1);
        if (!request.resourceKey().isBlank()) {
            if (repository.countResource(tenantId, request.resourceKey()) == 0) {
                throw new ConflictException("BENEFIT_RESOURCE_NOT_FOUND", request.resourceKey());
            }
        }
        Instant now = clock.instant();
        repository.saveBenefit(new BenefitFundingRepository.BenefitWrite(tenantId, benefitId, version,
                request.name(), request.status().name(), request.resourceKey(), request.benefitSkuId(),
                json(request.policy()), actorId, format(now)));
        repository.updateBenefitHead(tenantId, benefitId, version);
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
        int updated = repository.updateAccountFence(new BenefitFundingRepository.AccountFenceWrite(
                scope.tenantId().value(), resourceKey, next.fencingEpoch(), next.version(), next.state().name(),
                format(clock.instant()), current.fencingEpoch(), current.version()));
        if (updated != 1) throw new ConflictException("RESOURCE_CONCURRENT_MODIFICATION", resourceKey);
        if (current.type() == ResourceAccount.Type.BUDGET) {
            // fencing 是低频控制面动作；同一事务更新全部分桶，阻断旧纪元写者且不进入余额热路径。
            int expectedBuckets = repository.countBuckets(scope.tenantId().value(), resourceKey);
            int bucketUpdates = repository.updateBucketFence(new BenefitFundingRepository.BucketFenceWrite(
                    scope.tenantId().value(), resourceKey, next.fencingEpoch(), next.state().name(),
                    current.fencingEpoch(), format(clock.instant())));
            if (expectedBuckets == 0 || bucketUpdates != expectedBuckets) {
                throw new ConflictException("RESOURCE_BUCKET_FENCE_INCOMPLETE", resourceKey);
            }
        }
        return accountView(scope.tenantId().value(), next);
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
        repository.saveJourneyGrant(new BenefitFundingRepository.JourneyGrantWrite(command.tenantId(),
                command.commandId(), command.enrollmentId(), command.subjectToken(), command.journeyId(),
                command.journeyVersion(), resourceKey, command.payload().getOrDefault("benefitId", resourceKey),
                quantity, next.fencingEpoch(), "GRANTED", format(now)));
        ledger(command.tenantId(), command.commandId(), command.enrollmentId(), resourceKey,
                "GRANT", "AVAILABLE", "CONSUMED", quantity, next.version(), 0, null, now);
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
        List<ResourceAccount> accounts = loadAccountsForReserve(
                tenantId, demands.keySet().stream().sorted().toList());
        Map<String, ResourceAccount> byKey = new LinkedHashMap<>();
        accounts.forEach(account -> byKey.put(account.resourceKey(), account));
        Map<String, List<BalanceMutation>> mutations = new LinkedHashMap<>();
        for (ResourceDemand demand : demands.values().stream().sorted(Comparator.comparing(ResourceDemand::resourceKey)).toList()) {
            ResourceAccount account = byKey.get(demand.resourceKey());
            if (account == null) throw new ConflictException("REPRICE_REQUIRED", "resource is unavailable: " + demand.resourceKey());
            if (account.type() != demand.type() || !account.currency().equals(demand.currency())) {
                throw new ConflictException("RESOURCE_SEMANTICS_MISMATCH", demand.resourceKey());
            }
            long expectedEpoch = request.expectedFencingEpochs().get(demand.resourceKey());
            if (account.type() == ResourceAccount.Type.BUDGET) {
                mutations.put(demand.resourceKey(), reserveBudgetBuckets(
                        tenantId, account, demand.amount(), expectedEpoch, claims.quoteId()));
            } else {
                ResourceAccount next = account.reserve(demand.amount(), expectedEpoch);
                saveAccount(tenantId, next);
                byKey.put(demand.resourceKey(), next);
                mutations.put(demand.resourceKey(), List.of(new BalanceMutation(
                        next.fencingEpoch(), next.version(), 0, demand.amount())));
            }
        }
        String applicationId = UUID.randomUUID().toString();
        Instant now = clock.instant();
        boolean created = repository.trySaveApplication(new BenefitFundingRepository.ApplicationWrite(
                tenantId, applicationId, claims.quoteId(), claims.decisionRequestId(), request.orderId(),
                claims.organizationId(), json(new java.util.TreeSet<>(claims.shopIds())), request.cartDigest(),
                Digests.sha256Hex(request.offerToken()), claims.generation(), ApplicationState.RESERVED.name(),
                claims.totalDiscountMinorUnits(), claims.offerLines().getFirst().currency(),
                format(claims.expiresAt()), format(now), format(now), format(now)));
        if (!created) {
            throw new ConflictException("OFFER_TOKEN_REPLAYED", "offer token quote was already reserved");
        }
        for (ResourceDemand demand : demands.values()) {
            List<BalanceMutation> resourceMutations = mutations.get(demand.resourceKey());
            BalanceMutation firstMutation = resourceMutations.getFirst();
            repository.saveReservationItem(new BenefitFundingRepository.ReservationItemWrite(
                    tenantId, applicationId, demand.resourceKey(), demand.type().name(), demand.currency(),
                    demand.amount(), demand.amount(), 0, 0, 0, firstMutation.fencingEpoch()));
            if (demand.type() == ResourceAccount.Type.BUDGET) {
                for (BalanceMutation mutation : resourceMutations) {
                    repository.saveEscrowAllocation(new BenefitFundingRepository.EscrowAllocationWrite(
                            tenantId, applicationId, demand.resourceKey(), mutation.bucketId(), mutation.amount(),
                            mutation.amount(), 0, 0, 0, mutation.fencingEpoch()));
                    ledger(tenantId, applicationId, request.orderId(), demand.resourceKey(), "RESERVE",
                            "AVAILABLE", "RESERVED", mutation.amount(), mutation.version(), mutation.bucketId(),
                            null, now);
                }
            } else {
                ledger(tenantId, applicationId, request.orderId(), demand.resourceKey(), "RESERVE",
                        "AVAILABLE", "RESERVED", demand.amount(), firstMutation.version(), 0, null, now);
            }
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
        List<ExpiredApplication> candidates = repository.findExpiredApplications(
                        scope.tenantId().value(), format(now), limit).stream()
                .map(row -> new ExpiredApplication(row.tenantId(), row.applicationId())).toList();
        return expireCandidates(candidates, scope, now);
    }

    /** Each candidate is fenced and committed independently, so one poison row cannot roll back a batch. */
    public ExpirationResult expireDueReservations(int limit) {
        if (limit < 1 || limit > 1_000) throw new IllegalArgumentException("expiry limit must be in [1,1000]");
        Instant now = clock.instant();
        List<ExpiredApplication> candidates = repository.findExpiredApplications(null, format(now), limit).stream()
                .map(row -> new ExpiredApplication(row.tenantId(), row.applicationId())).toList();
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
        int updated = repository.expireApplication(
                candidate.tenantId(), candidate.applicationId(), format(now));
        if (updated != 1) return false;
        outbox(candidate.tenantId(), candidate.applicationId(), "PromotionExpired", now);
        return true;
    }

    private void recordExpirationFailure(ExpiredApplication candidate, RuntimeException failure, Instant now) {
        Integer attempts = repository.findExpiryAttemptsForUpdate(
                candidate.tenantId(), candidate.applicationId()).orElse(null);
        if (attempts == null) return;
        int nextAttempts = Math.addExact(attempts, 1);
        long delaySeconds = Math.min(300, 1L << Math.min(8, nextAttempts - 1));
        String error = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        if (error.length() > 1_000) error = error.substring(0, 1_000);
        repository.updateExpiryFailure(new BenefitFundingRepository.ExpiryFailureWrite(
                candidate.tenantId(), candidate.applicationId(), nextAttempts,
                format(now.plusSeconds(delaySeconds)), error));
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
            repository.updateApplicationState(
                    scope.tenantId().value(), applicationId, target.name(), format(now));
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
        repository.updateApplicationState(tenantId, applicationId, next.name(), format(now));
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
        loadAccountsForSettlement(tenantId, keys).forEach(account -> accounts.put(account.resourceKey(), account));
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
            if (account.type() == ResourceAccount.Type.BUDGET) {
                applyBudgetBuckets(tenantId, application, item, operation, amount, writerEpoch, now);
            } else {
                ResourceAccount next = switch (operation) {
                    case CONFIRM -> account.confirm(amount, writerEpoch);
                    case RELEASE -> account.release(amount, writerEpoch);
                    case REFUND -> account.refund(amount, writerEpoch);
                };
                saveAccount(tenantId, next);
                accounts.put(item.resourceKey(), next);
                String from = operation == Operation.CONFIRM || operation == Operation.RELEASE
                        ? "RESERVED" : "CONSUMED";
                String to = operation == Operation.CONFIRM ? "CONSUMED" : "AVAILABLE";
                ledger(tenantId, application.applicationId(), application.orderId(), item.resourceKey(),
                        operation.name(), from, to, amount, next.version(), 0,
                        operation == Operation.REFUND ? application.applicationId() : null, now);
            }
            updateReservationItem(tenantId, application.applicationId(), item.resourceKey(), operation, amount);
        }
    }

    /** 预算结算只能回写预留时记录的原分桶，防止退款或释放误入其他 escrow。 */
    private void applyBudgetBuckets(String tenantId, StoredApplication application, ReservationItem item,
            Operation operation, long amount, long writerEpoch, Instant now) {
        List<EscrowAllocation> allocations = repository.findEscrowAllocationsForUpdate(
                        tenantId, application.applicationId(), item.resourceKey()).stream()
                .map(row -> new EscrowAllocation(row.bucketId(), row.original(), row.reserved(),
                        row.consumed(), row.refunded(), row.released(), row.reservationEpoch()))
                .toList();
        long capacity = allocations.stream().mapToLong(allocation -> allocation.capacity(operation)).sum();
        if (allocations.isEmpty() || capacity < amount) {
            throw new ConflictException(operation == Operation.REFUND ? "REFUND_INVALID" : "RESERVATION_INVALID",
                    item.resourceKey());
        }
        long remaining = amount;
        for (EscrowAllocation allocation : allocations) {
            long part = Math.min(remaining, allocation.capacity(operation));
            if (part == 0) continue;
            ResourceAccount bucket = lockBudgetBucket(tenantId, item.resourceKey(), allocation.bucketId());
            ResourceAccount next = switch (operation) {
                case CONFIRM -> bucket.confirm(part, writerEpoch);
                case RELEASE -> bucket.release(part, writerEpoch);
                case REFUND -> bucket.refund(part, writerEpoch);
            };
            saveBudgetBucket(tenantId, allocation.bucketId(), next);
            String from = operation == Operation.REFUND ? "CONSUMED" : "RESERVED";
            String to = operation == Operation.CONFIRM ? "CONSUMED" : "AVAILABLE";
            ledger(tenantId, application.applicationId(), application.orderId(), item.resourceKey(),
                    operation.name(), from, to, part, next.version(), allocation.bucketId(),
                    operation == Operation.REFUND ? application.applicationId() : null, now);
            updateEscrowAllocation(tenantId, application.applicationId(), item.resourceKey(),
                    allocation.bucketId(), operation, part);
            remaining -= part;
            if (remaining == 0) break;
        }
        if (remaining != 0) throw new IllegalStateException("escrow allocation was not fully settled");
    }

    private void updateReservationItem(String tenantId, String applicationId, String resourceKey,
            Operation operation, long amount) {
        int updated = repository.updateReservationAmounts(new BenefitFundingRepository.ReservationMutation(
                tenantId, applicationId, resourceKey, operation.name(), amount));
        if (updated != 1) throw new ConflictException("RESERVATION_CONCURRENT_MODIFICATION", resourceKey);
    }

    private void updateEscrowAllocation(String tenantId, String applicationId, String resourceKey,
            int bucketId, Operation operation, long amount) {
        int updated = repository.updateEscrowAllocationAmounts(new BenefitFundingRepository.EscrowMutation(
                tenantId, applicationId, resourceKey, bucketId, operation.name(), amount));
        if (updated != 1) throw new ConflictException("ESCROW_ALLOCATION_CONCURRENT_MODIFICATION", resourceKey);
    }

    @Transactional(readOnly = true)
    public ReconciliationReport reconcile() {
        var scope = TenantContextHolder.requireCurrent();
        scope.requirePermission("funding:reconcile");
        String tenantId = scope.tenantId().value();
        List<String> violations = new ArrayList<>();
        List<ResourceAccount> roots = repository.findAccounts(tenantId, false);
        List<ResourceSnapshot> resources = roots.stream().map(account -> {
            AccountView view = accountView(tenantId, account);
            return new ResourceSnapshot(view.resourceKey(), view.authorized(), view.available(),
                    view.reserved(), view.consumed(), view.returned());
        }).toList();
        Set<String> budgetResources = roots.stream()
                .filter(account -> account.type() == ResourceAccount.Type.BUDGET)
                .map(ResourceAccount::resourceKey).collect(java.util.stream.Collectors.toUnmodifiableSet());
        Map<String, ReplayBalance> balances = new LinkedHashMap<>();
        resources.forEach(resource -> balances.put(resource.resourceKey(), new ReplayBalance(resource.authorized())));
        Map<ApplicationResourceKey, ReservationReplay> reservationReplay = new LinkedHashMap<>();
        List<LedgerEntry> ledger = repository.findLedger(tenantId).stream()
                .map(row -> new LedgerEntry(row.applicationId(), row.resourceKey(), row.escrowBucketId(),
                        row.operation(), row.debitBucket(), row.creditBucket(), row.amount(), row.accountVersion()))
                .toList();
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
        List<ReconcileItem> items = repository.findReconcileItems(tenantId).stream()
                .map(row -> new ReconcileItem(row.applicationId(), row.resourceKey(), row.original(),
                        row.reserved(), row.consumed(), row.refunded(), row.released(), row.reservationEpoch()))
                .toList();
        Map<String, List<ReconcileItem>> itemsByApplication = new LinkedHashMap<>();
        Set<ApplicationResourceKey> storedItemKeys = new java.util.HashSet<>();
        Map<ApplicationResourceKey, ReconcileItem> escrowAllocations = new LinkedHashMap<>();
        repository.findEscrowReconcileItems(tenantId).forEach(row ->
                escrowAllocations.put(new ApplicationResourceKey(row.applicationId(), row.resourceKey()),
                        new ReconcileItem(row.applicationId(), row.resourceKey(), row.original(), row.reserved(),
                                row.consumed(), row.refunded(), row.released(),
                                row.minReservationEpoch() == row.maxReservationEpoch()
                                        ? row.minReservationEpoch() : -1)));
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
            if (budgetResources.contains(item.resourceKey())) {
                ReconcileItem allocation = escrowAllocations.get(
                        new ApplicationResourceKey(item.applicationId(), item.resourceKey()));
                if (allocation == null || !sameReservationAmounts(item, allocation)) {
                    violations.add(item.applicationId() + ':' + item.resourceKey()
                            + ":ESCROW_ALLOCATION_MISMATCH");
                }
            }
        }
        reservationReplay.keySet().stream().filter(key -> !storedItemKeys.contains(key)).forEach(key ->
                violations.add(key.applicationId() + ':' + key.resourceKey() + ":RESERVATION_ITEM_MISSING"));
        Map<String, Set<String>> outboxTypes = new LinkedHashMap<>();
        repository.findOutboxTypes(tenantId).forEach(row -> outboxTypes
                .computeIfAbsent(row.aggregateId(), ignored -> new java.util.HashSet<>()).add(row.eventType()));
        repository.findApplicationStates(tenantId).forEach(row -> {
            String applicationId = row.applicationId();
            ApplicationState state = ApplicationState.valueOf(row.stateName());
            List<ReconcileItem> applicationItems = itemsByApplication.getOrDefault(applicationId, List.of());
            if (applicationItems.isEmpty() || !stateMatches(state, applicationItems)) {
                violations.add(applicationId + ":APPLICATION_RESERVATION_MISMATCH");
            }
            Set<String> events = outboxTypes.getOrDefault(applicationId, Set.of());
            if (!events.contains("PromotionReserved") || !events.contains(expectedStateEvent(state))) {
                violations.add(applicationId + ":OUTBOX_INCOMPLETE");
            }
        });
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

    private static boolean sameReservationAmounts(ReconcileItem item, ReconcileItem allocation) {
        return item.original() == allocation.original()
                && item.reserved() == allocation.reserved()
                && item.consumed() == allocation.consumed()
                && item.refunded() == allocation.refunded()
                && item.released() == allocation.released()
                && item.reservationEpoch() == allocation.reservationEpoch();
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

    /** 新建预算账户时均匀授予 escrow，后续余额写不再竞争资源账户主行。 */
    private void createBudgetBuckets(String tenantId, ResourceAccount account) {
        long quotient = account.authorized() / budgetBucketCount;
        long remainder = account.authorized() % budgetBucketCount;
        Instant now = clock.instant();
        for (int bucketId = 0; bucketId < budgetBucketCount; bucketId++) {
            long authorized = quotient + (bucketId < remainder ? 1 : 0);
            repository.saveBucket(new BenefitFundingRepository.BucketWrite(tenantId, account.resourceKey(),
                    bucketId, authorized, authorized, 0, 0, 0, account.fencingEpoch(), 0,
                    account.state().name(), format(now)));
        }
    }

    /**
     * 先以 quote/resource 稳定散列选择分桶，再用单行条件更新抢占额度。
     * 正常请求只锁一个 bucket；候选桶被并发耗尽时才探测下一个桶。
     */
    private List<BalanceMutation> reserveBudgetBuckets(String tenantId, ResourceAccount account, long amount,
            long expectedEpoch, String quoteId) {
        List<Integer> bucketIds = repository.findEligibleBucketIds(
                tenantId, account.resourceKey(), expectedEpoch, amount);
        if (!bucketIds.isEmpty()) {
            int start = Math.floorMod((quoteId + '|' + account.resourceKey()).hashCode(), bucketIds.size());
            for (int offset = 0; offset < bucketIds.size(); offset++) {
                int bucketId = bucketIds.get((start + offset) % bucketIds.size());
                int updated = repository.reserveBucket(new BenefitFundingRepository.BucketReserveWrite(
                        tenantId, account.resourceKey(), bucketId, expectedEpoch, amount,
                        format(clock.instant())));
                if (updated == 0) continue;
                long version = repository.findBucketVersion(tenantId, account.resourceKey(), bucketId)
                        .orElseThrow(() -> new IllegalStateException("updated escrow bucket disappeared"));
                return List.of(new BalanceMutation(expectedEpoch, version, bucketId, amount));
            }
        }
        // 大额、碎片化或候选桶被并发抢占时进入慢路径：按 bucket_id 全序加锁，避免多桶反向加锁死锁。
        List<BudgetBucket> buckets = repository.findBucketsForUpdate(tenantId, account.resourceKey()).stream()
                .map(row -> new BudgetBucket(row.bucketId(), budgetAccount(account, row)))
                .toList();
        long available = buckets.stream().filter(bucket -> bucket.account().state() == ResourceAccount.State.ACTIVE
                        && bucket.account().fencingEpoch() == expectedEpoch)
                .mapToLong(bucket -> bucket.account().available()).sum();
        if (available >= amount) {
            List<BalanceMutation> mutations = new ArrayList<>();
            long remaining = amount;
            for (BudgetBucket bucket : buckets) {
                if (remaining == 0) break;
                if (bucket.account().state() != ResourceAccount.State.ACTIVE
                        || bucket.account().fencingEpoch() != expectedEpoch) continue;
                long part = Math.min(remaining, bucket.account().available());
                if (part == 0) continue;
                ResourceAccount next = bucket.account().reserve(part, expectedEpoch);
                saveBudgetBucket(tenantId, bucket.bucketId(), next);
                mutations.add(new BalanceMutation(expectedEpoch, next.version(), bucket.bucketId(), part));
                remaining -= part;
            }
            if (remaining != 0) throw new IllegalStateException("locked budget escrow was not fully allocated");
            return List.copyOf(mutations);
        }
        ResourceAccount current = loadAccount(tenantId, account.resourceKey(), false);
        if (current.fencingEpoch() != expectedEpoch) {
            throw new ConflictException("FENCING_EPOCH_MISMATCH", account.resourceKey());
        }
        if (current.state() != ResourceAccount.State.ACTIVE) {
            throw new ConflictException("RESOURCE_FROZEN", account.resourceKey());
        }
        throw new ConflictException("REPRICE_REQUIRED",
                "resource capacity is insufficient: " + account.resourceKey());
    }

    private List<ResourceAccount> loadAccountsForReserve(String tenantId, List<String> keys) {
        return loadAccountsWithBudgetEscrow(tenantId, keys);
    }

    private List<ResourceAccount> loadAccountsForSettlement(String tenantId, List<String> keys) {
        return loadAccountsWithBudgetEscrow(tenantId, keys);
    }

    /** 预算只读主行元数据，余额由 bucket 原子更新；其他资源继续锁单账户行。 */
    private List<ResourceAccount> loadAccountsWithBudgetEscrow(String tenantId, List<String> keys) {
        List<ResourceAccount> accounts = new ArrayList<>();
        for (String key : keys) {
            ResourceAccount account = repository.findAccount(tenantId, key, false).orElse(null);
            if (account == null) continue;
            if (account.type() == ResourceAccount.Type.BUDGET) accounts.add(account);
            else accounts.add(loadAccount(tenantId, key, true));
        }
        return accounts;
    }

    private ResourceAccount loadAccount(String tenantId, String resourceKey, boolean lock) {
        return repository.findAccount(tenantId, resourceKey, lock)
                .orElseThrow(() -> new NotFoundException("RESOURCE_NOT_FOUND", resourceKey));
    }

    private ResourceAccount lockBudgetBucket(String tenantId, String resourceKey, int bucketId) {
        BenefitFundingRepository.BudgetBucketRow row = repository.findBucketForUpdate(
                tenantId, resourceKey, bucketId)
                .orElseThrow(() -> new ConflictException("RESOURCE_BUCKET_NOT_FOUND", resourceKey));
        return new ResourceAccount(resourceKey, ResourceAccount.Type.BUDGET, "ESCROW", row.authorized(),
                row.available(), row.reserved(), row.consumed(), row.returned(), row.fencingEpoch(),
                row.version(), ResourceAccount.State.valueOf(row.state()));
    }

    private void saveBudgetBucket(String tenantId, int bucketId, ResourceAccount bucket) {
        int updated = repository.updateBucketBalance(new BenefitFundingRepository.BucketBalanceWrite(
                tenantId, bucket.resourceKey(), bucketId, bucket.available(), bucket.reserved(),
                bucket.consumed(), bucket.returned(), bucket.version(), bucket.version() - 1,
                bucket.fencingEpoch(), format(clock.instant())));
        if (updated != 1) throw new ConflictException("RESOURCE_CONCURRENT_MODIFICATION", bucket.resourceKey());
    }

    /** 账户查询聚合所有 escrow，主行只保留稳定资源元数据和控制面 fencing。 */
    private AccountView accountView(String tenantId, ResourceAccount account) {
        if (account.type() != ResourceAccount.Type.BUDGET) return AccountView.from(account);
        BenefitFundingRepository.BucketTotals totals = repository.summarizeBuckets(
                tenantId, account.resourceKey());
        if (totals == null || totals.count() == 0 || totals.authorized() != account.authorized()
                || totals.minFencingEpoch() != account.fencingEpoch()
                || totals.maxFencingEpoch() != account.fencingEpoch() || totals.distinctStates() != 1
                || !account.state().name().equals(totals.state())) {
            throw new IllegalStateException("budget escrow conservation metadata is invalid: " + account.resourceKey());
        }
        ResourceAccount aggregate = new ResourceAccount(account.resourceKey(), account.type(), account.currency(),
                totals.authorized(), totals.available(), totals.reserved(), totals.consumed(), totals.returned(),
                account.fencingEpoch(), Math.max(account.version(), totals.maxVersion()), account.state());
        return AccountView.from(aggregate);
    }

    private List<ResourceAccount> lockAccounts(String tenantId, List<String> keys) {
        List<ResourceAccount> accounts = new ArrayList<>();
        for (String key : keys) {
            repository.findAccount(tenantId, key, true).ifPresent(accounts::add);
        }
        return accounts;
    }

    private static ResourceAccount budgetAccount(ResourceAccount root,
            BenefitFundingRepository.BudgetBucketRow row) {
        return new ResourceAccount(root.resourceKey(), ResourceAccount.Type.BUDGET, root.currency(),
                row.authorized(), row.available(), row.reserved(), row.consumed(), row.returned(),
                row.fencingEpoch(), row.version(), ResourceAccount.State.valueOf(row.state()));
    }

    private BenefitView benefitView(BenefitFundingRepository.BenefitRow row) {
        return new BenefitView(row.benefitId(), row.version(), row.name(), BenefitStatus.valueOf(row.status()),
                row.resourceKey(), row.benefitSkuId(), readMap(row.policyJson()), row.createdBy(),
                Instant.parse(row.createdAt()));
    }

    private void saveAccount(String tenantId, ResourceAccount account) {
        int count = repository.updateAccountBalance(new BenefitFundingRepository.AccountBalanceWrite(
                tenantId, account.resourceKey(), account.available(), account.reserved(), account.consumed(),
                account.returned(), account.version(), account.version() - 1, format(clock.instant())));
        if (count != 1) throw new ConflictException("RESOURCE_CONCURRENT_MODIFICATION", account.resourceKey());
    }

    private StoredApplication lockApplication(String tenantId, String applicationId) {
        BenefitFundingRepository.StoredApplicationRow row = repository.findApplicationForUpdate(
                tenantId, applicationId)
                .orElseThrow(() -> new NotFoundException(
                        "APPLICATION_NOT_FOUND", "promotion application not found"));
        return new StoredApplication(row.applicationId(), row.orderId(), row.organizationId(),
                stringSet(row.shopIdsJson()), ApplicationState.valueOf(row.state()), Instant.parse(row.expiresAt()));
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
        return repository.findReservationItems(tenantId, applicationId, true).stream()
                .map(row -> new ReservationItem(row.resourceKey(), row.originalAmount(), row.reservedAmount(),
                        row.consumedAmount(), row.refundedAmount(), row.releasedAmount(), row.reservationEpoch()))
                .toList();
    }

    private ApplicationView application(String tenantId, String applicationId) {
        BenefitFundingRepository.ApplicationRow row = repository.findApplication(tenantId, applicationId)
                .orElseThrow(() -> new NotFoundException(
                        "APPLICATION_NOT_FOUND", "promotion application not found"));
        return new ApplicationView(applicationId, row.quoteId(), row.orderId(), row.cartDigest(), row.generation(),
                ApplicationState.valueOf(row.state()), row.totalDiscount(), row.currency(),
                items(tenantId, applicationId), Instant.parse(row.expiresAt()), Instant.parse(row.createdAt()),
                Instant.parse(row.updatedAt()));
    }

    private List<ItemView> items(String tenantId, String applicationId) {
        return repository.findItems(tenantId, applicationId).stream()
                .map(row -> new ItemView(row.resourceKey(), ResourceAccount.Type.valueOf(row.type()),
                        row.currency(), row.originalAmount(), row.reservedAmount(), row.consumedAmount(),
                        row.refundedAmount(), row.releasedAmount(), row.reservationEpoch()))
                .toList();
    }

    private <T> T command(String tenantId, String commandId, String payloadHash, Class<T> type, Supplier<T> action) {
        if (commandId == null || !commandId.matches("[a-zA-Z0-9_.:-]{8,128}")) {
            throw new IllegalArgumentException("command id is invalid");
        }
        Instant now = clock.instant();
        boolean owner = repository.tryBeginCommand(new BenefitFundingRepository.CommandWrite(
                tenantId, commandId, payloadHash, "PROCESSING", format(now),
                format(now.plusSeconds(604_800))));
        // Existing command row is locked/read below; committed original response is returned byte-for-byte。
        BenefitFundingRepository.CommandRow stored = repository.findCommandForUpdate(tenantId, commandId)
                .orElseThrow(() -> new IllegalStateException("command deduplication row disappeared"));
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
        repository.completeCommand(tenantId, commandId, json(response));
        return response;
    }

    private void ledger(String tenantId, String applicationId, String orderId, String resourceKey,
            String operation, String debitBucket, String creditBucket, long amount, long accountVersion,
            int escrowBucketId, String correctionOf, Instant now) {
        repository.saveLedger(new BenefitFundingRepository.LedgerWrite(tenantId, UUID.randomUUID().toString(),
                applicationId, orderId, resourceKey, escrowBucketId, operation, debitBucket, creditBucket,
                amount, accountVersion, correctionOf, format(now)));
    }

    private void outbox(String tenantId, String applicationId, String type, Instant now) {
        long sequence = nextOutboxSequence(tenantId, applicationId, now);
        String eventId = UUID.randomUUID().toString();
        repository.saveOutbox(new BenefitFundingRepository.OutboxWrite(tenantId, eventId, applicationId,
                type, "mk.benefit.event.v1", tenantId + ':' + applicationId, sequence,
                json(Map.of("eventId", eventId, "eventType", type, "tenantId", tenantId,
                        "aggregateId", applicationId, "occurredAt", format(now))),
                format(now), format(now)));
    }

    private long nextOutboxSequence(String tenantId, String aggregateId, Instant now) {
        int updated = repository.incrementOutboxPosition(tenantId, aggregateId, format(now));
        if (updated == 0) {
            if (!repository.tryCreateOutboxPosition(tenantId, aggregateId, format(now))) {
                repository.incrementOutboxPosition(tenantId, aggregateId, format(now));
            }
        }
        return repository.findOutboxPosition(tenantId, aggregateId)
                .orElseThrow(() -> new IllegalStateException("benefit outbox sequence allocation failed"));
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JacksonException failure) { throw new IllegalArgumentException("command cannot be serialized", failure); }
    }
    private <T> T read(String value, Class<T> type) {
        try { return mapper.readValue(value, type); }
        catch (JacksonException failure) { throw new IllegalStateException("stored command response is invalid", failure); }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String value) {
        return read(value, Map.class);
    }

    private enum Operation { CONFIRM, RELEASE, REFUND }
    public enum ApplicationState { RESERVED, CONFIRMED, CANCELLED, EXPIRED, PARTIALLY_REFUNDED, REFUNDED, REVERSED }
    public enum BenefitStatus { DRAFT, ACTIVE, RETIRED }
    private record ResourceSnapshot(String resourceKey, long authorized, long available, long reserved,
            long consumed, long returned) { }
    private record LedgerEntry(String applicationId, String resourceKey, int escrowBucketId, String operation,
            String debitBucket, String creditBucket, long amount, long accountVersion) { }
    private record BalanceMutation(long fencingEpoch, long version, int bucketId, long amount) { }
    private record BudgetBucket(int bucketId, ResourceAccount account) { }
    private record EscrowAllocation(int bucketId, long original, long reserved, long consumed,
            long refunded, long released, long reservationEpoch) {
        private long capacity(Operation operation) {
            return operation == Operation.REFUND ? consumed : reserved;
        }
    }
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
    private record ReleaseBenefit(String status, String benefitSkuId, Map<String, Object> policy) { }
    private record BenefitVersion(String benefitId, long version) {
        private static BenefitVersion parse(String value) {
            int separator = value == null ? -1 : value.lastIndexOf('@');
            if (separator < 1 || separator == value.length() - 1) {
                throw new ConflictException("BENEFIT_RELEASE_REFERENCE_INVALID",
                        "benefitDefinitionVersion must use benefitId@positiveVersion");
            }
            try {
                long version = Long.parseLong(value.substring(separator + 1));
                if (version < 1) throw new NumberFormatException("non-positive version");
                return new BenefitVersion(value.substring(0, separator), version);
            } catch (NumberFormatException invalid) {
                throw new ConflictException("BENEFIT_RELEASE_REFERENCE_INVALID",
                        "benefitDefinitionVersion must use benefitId@positiveVersion");
            }
        }
    }
    private record StoredApplication(String applicationId, String orderId, String organizationId,
            Set<String> shopIds, ApplicationState state,
            Instant expiresAt) { }
    private record ExpiredApplication(String tenantId, String applicationId) { }
    private record ReservationItem(String resourceKey, long originalAmount, long reservedAmount,
            long consumedAmount, long refundedAmount, long releasedAmount, long reservationEpoch) { }
    public record CreateAccountRequest(String resourceKey, ResourceAccount.Type type, String currency,
            long authorized, Long fencingEpoch) {
        /** 使用包装类型区分缺字段，确保在请求边界返回稳定、可读的 problem。 */
        public CreateAccountRequest {
            if (fencingEpoch == null || fencingEpoch < 1) {
                throw new IllegalArgumentException("fencingEpoch must be greater than or equal to 1");
            }
        }
    }
    public record ReleaseEligibility(boolean releasable, Set<String> references) {
        public ReleaseEligibility { references = Set.copyOf(references); }
    }
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
