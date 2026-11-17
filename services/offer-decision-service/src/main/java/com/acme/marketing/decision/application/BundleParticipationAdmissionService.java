package com.acme.marketing.decision.application;

import static com.acme.marketing.platform.time.SqlTime.format;

import com.acme.marketing.contracts.release.*;
import com.acme.marketing.platform.crypto.TrustedPublicKeys;
import com.acme.marketing.platform.identity.TenantScope;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

/** 一次参与只绑定一个完整组合；RECORDED、进程缓存和客户端版本都不能成为新准入来源。 */
@Service
public class BundleParticipationAdmissionService {
    public static final String ADMIT_PERMISSION = "bundle:admit";
    public static final int MAX_REQUEST_BYTES = 8 * 1024;
    private static final int MAX_PARTICIPATION_BYTES = 16 * 1024;
    private static final int MAX_ACTOR_ID_LENGTH = 128;
    private static final int TRANSACTION_TIMEOUT_SECONDS = 10;
    private final BundleAdmissionRepository repository;
    private final BundleParticipationSigningService signing;
    private final PinnedReleaseBundleActivationVerifier activationVerifier;
    private final PinnedReleaseBundleParticipationVerifier participationVerifier;
    private final Map<String, PublicKey> participationKeys;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final String environment;
    private final String cell;

    /** 签发与消费各用明确的参与角色信任，缺少私钥时仍能恢复已存在的事实。 */
    public BundleParticipationAdmissionService(BundleAdmissionRepository repository, BundleParticipationSigningService signing,
            PinnedReleaseBundleActivationVerifier activationVerifier, ObjectMapper mapper, Clock clock,
            @Value("${marketing.participation.trusted-key-id:}") String keyId,
            @Value("${marketing.participation.public-key-base64:}") String encodedKey,
            @Value("${marketing.participation.trusted-public-keys:}") String additionalKeys,
            @Value("${marketing.runtime.environment:local}") String environment,
            @Value("${marketing.runtime.cell:cell-a}") String cell) {
        this.repository = repository; this.signing = signing; this.activationVerifier = activationVerifier;
        this.mapper = mapper; this.clock = clock; this.environment = environment; this.cell = cell;
        this.participationKeys = TrustedPublicKeys.parse(keyId, encodedKey, additionalKeys);
        this.participationVerifier = new PinnedReleaseBundleParticipationVerifier(participationKeys, activationVerifier);
    }

    /** 共享指针锁先于任何快照；唯一插入竞争后在READ_COMMITTED下读取获胜的不可变事实。 */
    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = TRANSACTION_TIMEOUT_SECONDS)
    public ReleaseBundleParticipation admit(TenantScope scope, ReleaseBundleAdmissionRequest request) {
        scope.requirePermission(ADMIT_PERMISSION);
        if (request == null || scope.actorId().length() > MAX_ACTOR_ID_LENGTH) throw ReleaseBundleProtocolError.ADMISSION_REQUEST_INVALID.invalid();
        String subjectHash = ReleaseBundleParticipation.subjectHash(scope.tenantId(), request.subjectToken());
        var key = new BundleAdmissionRepository.Key(scope.tenantId().value(), environment, cell, request.campaignId());
        var current = repository.lockApplied(key).orElseThrow(ReleaseBundleProtocolError.ADMISSION_NOT_APPLIED::conflict);
        var existing = repository.participation(key, request.participationId());
        if (existing.isPresent()) return restore(scope, request, key, subjectHash, existing.orElseThrow());
        Instant now = clock.instant();
        var source = source(key, current, now);
        activationVerifier.requireValid(key.tenantId(), source, now);
        requireScope(scope, source);
        var bundle = source.bundle();
        var proof = signing.sign(new ReleaseBundleParticipation(UUID.randomUUID().toString(), scope.tenantId(), request.campaignId(),
                environment, cell, request.participationId(), subjectHash, source.directiveId(), source.activationSequence(), source.signature(),
                bundle.bundleId(), bundle.version(), bundle.contentHash(), now, signing.keyId(), ""));
        if (!ReleaseBundleParticipationSigner.verify(participationKeys.get(proof.signatureKeyId()), proof))
            throw ReleaseBundleProtocolError.PARTICIPATION_SIGNING_UNAVAILABLE.conflict();
        participationVerifier.requireValid(expected(scope, request), proof, source, now);
        String content = mapper.writeValueAsString(proof);
        if (content.getBytes(StandardCharsets.UTF_8).length > MAX_PARTICIPATION_BYTES)
            throw ReleaseBundleProtocolError.PARTICIPATION_STORED_INVALID.conflict();
        var stored = new BundleAdmissionRepository.StoredParticipation(proof.bindingId(), subjectHash, proof.activationSequence(),
                content, scope.actorId(), format(now));
        if (repository.insert(key, request.participationId(), stored)) return proof;
        // 仅完整已提交的获胜行可以重放；不得返回本事务未保存的候选签名。
        return restore(scope, request, key, subjectHash, repository.participation(key, request.participationId())
                .orElseThrow(ReleaseBundleProtocolError.PARTICIPATION_STORED_INVALID::conflict));
    }

    private ReleaseBundleParticipation restore(TenantScope scope, ReleaseBundleAdmissionRequest request,
            BundleAdmissionRepository.Key key, String subjectHash, BundleAdmissionRepository.StoredParticipation stored) {
        ReleaseBundleParticipation proof = read(stored.participationJson(), ReleaseBundleParticipation.class, MAX_PARTICIPATION_BYTES);
        if (!proof.bindingId().equals(stored.bindingId()) || !proof.subjectHash().equals(stored.subjectHash())
                || proof.activationSequence() != stored.activationSequence() || !format(proof.admittedAt()).equals(stored.createdAt()))
            throw ReleaseBundleProtocolError.PARTICIPATION_STORED_INVALID.conflict();
        var applied = repository.applied(key, stored.activationSequence())
                .orElseThrow(ReleaseBundleProtocolError.PARTICIPATION_STORED_INVALID::conflict);
        var source = source(key, applied, proof.admittedAt());
        // 先核验来源并检查当前资源范围，不能让越权调用通过主体冲突响应探测绑定归属。
        activationVerifier.requireValid(key.tenantId(), source, proof.admittedAt());
        requireScope(scope, source);
        if (!subjectHash.equals(stored.subjectHash())) throw ReleaseBundleProtocolError.PARTICIPATION_SUBJECT_CONFLICT.conflict();
        participationVerifier.requireValid(expected(scope, request), proof, source, clock.instant());
        return proof;
    }

    private ReleaseBundleActivationDirective source(BundleAdmissionRepository.Key key, BundleAdmissionRepository.AppliedAuthorization stored, Instant admittedAt) {
        var value = read(stored.directiveJson(), ReleaseBundleActivationDirective.class, DecisionBundleDirectiveService.MAX_REQUEST_BYTES);
        var bundle = value.bundle();
        if (!key.tenantId().equals(bundle.tenantId().value()) || !key.environment().equals(bundle.environment())
                || !key.cell().equals(bundle.cell()) || !key.campaignId().equals(bundle.campaignId())
                || stored.activationSequence() != value.activationSequence() || !stored.directiveId().equals(value.directiveId()))
            throw ReleaseBundleProtocolError.ACTIVATION_STORED_INVALID.conflict();
        try {
            Instant appliedAt = Instant.parse(stored.appliedAt());
            if (appliedAt.isBefore(value.authorizedAt()) || appliedAt.isAfter(admittedAt) || !appliedAt.isBefore(bundle.expiresAt()))
                throw ReleaseBundleProtocolError.ACTIVATION_STORED_INVALID.conflict();
        } catch (java.time.DateTimeException corrupt) { throw ReleaseBundleProtocolError.ACTIVATION_STORED_INVALID.conflict(); }
        return value;
    }

    private <T> T read(String content, Class<T> type, int maximumBytes) {
        try {
            if (content == null || content.getBytes(StandardCharsets.UTF_8).length > maximumBytes)
                throw ReleaseBundleProtocolError.PARTICIPATION_STORED_INVALID.conflict();
            T value = mapper.readerFor(type).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(content);
            if (value == null) throw ReleaseBundleProtocolError.PARTICIPATION_STORED_INVALID.conflict();
            return value;
        } catch (JacksonException | IllegalArgumentException corrupt) { throw ReleaseBundleProtocolError.PARTICIPATION_STORED_INVALID.conflict(); }
    }

    private PinnedReleaseBundleParticipationVerifier.ExpectedContext expected(TenantScope scope, ReleaseBundleAdmissionRequest request) {
        return new PinnedReleaseBundleParticipationVerifier.ExpectedContext(scope.tenantId(), request.campaignId(), environment, cell,
                request.participationId(), request.subjectToken());
    }
    private static void requireScope(TenantScope scope, ReleaseBundleActivationDirective source) {
        scope.requireOrganization(source.organizationId());
        if (!source.shopId().isEmpty()) scope.requireShop(source.shopId());
    }
}
