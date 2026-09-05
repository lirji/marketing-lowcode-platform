package com.acme.marketing.benefit.application;

import com.acme.marketing.contracts.offer.OfferLineClaim;
import com.acme.marketing.contracts.offer.OfferTokenClaims;
import com.acme.marketing.contracts.offer.OfferTokenCodec;
import com.acme.marketing.platform.crypto.Digests;
import com.acme.marketing.platform.error.ConflictException;
import com.acme.marketing.platform.identity.TenantId;
import com.acme.marketing.platform.identity.TenantScope;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 从服务端签名的 OfferToken 重建 AwardIntent。SKU、权益类型和金额均来自服务端权威快照，
 * 调用方不能通过请求体覆盖发奖金额或渠道。
 */
@Component
public final class AwardIntentAssembler {
    static final String SOURCE_SYSTEM = "drools-activity";
    private static final int MAX_ITEMS = 20;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final OfferTokenTrust tokenTrust;
    private final BenefitSkuCatalog skuCatalog;

    /** 构造只依赖可验证决策、营销定义库和权益目录端口的组装器。 */
    public AwardIntentAssembler(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock,
            OfferTokenTrust tokenTrust, BenefitSkuCatalog skuCatalog) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
        this.tokenTrust = tokenTrust;
        this.skuCatalog = skuCatalog;
    }

    /**
     * 校验签名决策、主体与组织范围，并按不可变 BenefitDefinition 版本组装原子发奖项。
     */
    public AssembledIntent assemble(TenantScope scope, AssembleCommand command) {
        OfferTokenClaims claims = OfferTokenCodec.verify(command.offerToken(), tokenTrust::resolve,
                new TenantId(scope.tenantId().value()), command.cartDigest(), clock);
        scope.requireOrganization(claims.organizationId());
        claims.shopIds().forEach(scope::requireShop);
        if (!claims.subjectToken().equals(command.subjectRef())) {
            throw new ConflictException("AWARD_SUBJECT_MISMATCH",
                    "award subject does not match the authoritative decision");
        }
        if (claims.decisionRequestId().length() > 128) {
            throw new ConflictException("AWARD_DECISION_REFERENCE_INVALID",
                    "authoritative decision request id exceeds the benefit-center contract");
        }

        List<AwardItemIntent> items = new ArrayList<>();
        for (int lineIndex = 0; lineIndex < claims.offerLines().size(); lineIndex++) {
            OfferLineClaim line = claims.offerLines().get(lineIndex);
            BenefitVersion reference = BenefitVersion.parse(line.benefitDefinitionVersion());
            BoundBenefit benefit = activeBenefit(scope.tenantId().value(), reference);
            BenefitSkuCatalog.BenefitSkuView sku = skuCatalog.requireActiveSku(
                    scope.tenantId().value(), benefit.benefitSkuId());
            BenefitType type = benefitType(sku.benefitType());
            for (int unit = 0; unit < line.quantity(); unit++) {
                if (items.size() >= MAX_ITEMS) {
                    throw new ConflictException("AWARD_ITEM_LIMIT_EXCEEDED",
                            "one AwardIntent can contain at most 20 atomic items");
                }
                Long amountMinor = type == BenefitType.CASH ? positiveCashAmount(line) : null;
                String currency = type == BenefitType.CASH ? line.currency() : null;
                items.add(new AwardItemIntent("line-" + (lineIndex + 1) + "-unit-" + (unit + 1),
                        benefit.benefitSkuId(), type, amountMinor, currency, 1, Map.of()));
            }
        }
        if (items.isEmpty()) {
            throw new ConflictException("AWARD_NOT_ELIGIBLE",
                    "authoritative decision did not produce an award");
        }

        Map<String, String> trace = new LinkedHashMap<>();
        trace.put("campaignId", command.campaignId());
        trace.put("definitionVersion", Long.toString(command.definitionVersion()));
        trace.put("generation", Long.toString(claims.generation()));
        trace.put("quoteId", claims.quoteId());
        AwardIntent intent = new AwardIntent("1.0", SOURCE_SYSTEM, command.sourceRequestId(),
                claims.orderId().isBlank() ? null : claims.orderId(), claims.subjectToken(),
                new DecisionReference(claims.decisionRequestId(), command.campaignId(),
                        Math.toIntExact(command.definitionVersion())),
                PartialPolicy.BEST_EFFORT, items, trace);
        String payload = json(intent);
        return new AssembledIntent(intent, payload, Digests.sha256Hex(payload),
                subjectHash(scope.tenantId().value(), claims.subjectToken()));
    }

    private BoundBenefit activeBenefit(String tenantId, BenefitVersion reference) {
        List<BoundBenefit> rows = jdbc.query(
                "select benefit_id,version_no,status_name,benefit_sku_id from mk_benefit_definition where tenant_id=? and benefit_id=? and version_no=?",
                (rs, rowNum) -> new BoundBenefit(rs.getString(1), rs.getLong(2),
                        rs.getString(3), rs.getString(4)),
                tenantId, reference.benefitId(), reference.version());
        if (rows.isEmpty()) {
            throw new ConflictException("AWARD_BENEFIT_VERSION_NOT_FOUND",
                    "benefit definition version does not exist: " + reference.original());
        }
        BoundBenefit benefit = rows.getFirst();
        if (!"ACTIVE".equals(benefit.status()) || benefit.benefitSkuId() == null
                || benefit.benefitSkuId().isBlank()) {
            throw new ConflictException("AWARD_BENEFIT_NOT_ACTIVE",
                    "benefit definition version is not ACTIVE and bound to a SKU: " + reference.original());
        }
        return benefit;
    }

    private static BenefitType benefitType(String value) {
        try {
            return BenefitType.valueOf(value);
        } catch (RuntimeException invalid) {
            throw new ConflictException("AWARD_BENEFIT_TYPE_UNSUPPORTED",
                    "benefit-center returned an unsupported benefit type: " + value);
        }
    }

    private static long positiveCashAmount(OfferLineClaim line) {
        if (line.minorUnits() <= 0 || line.currency() == null || line.currency().length() != 3) {
            throw new ConflictException("AWARD_CASH_AMOUNT_INVALID",
                    "authoritative decision has no positive cash amount");
        }
        return line.minorUnits();
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JacksonException failure) {
            throw new IllegalStateException("AwardIntent cannot be serialized", failure);
        }
    }

    static String subjectHash(String tenantId, String subjectRef) {
        return Digests.sha256Hex(tenantId + '\u0000' + subjectRef);
    }

    private record BoundBenefit(String benefitId, long version, String status, String benefitSkuId) { }

    private record BenefitVersion(String original, String benefitId, long version) {
        private static BenefitVersion parse(String value) {
            int separator = value == null ? -1 : value.lastIndexOf('@');
            if (separator < 1 || separator == value.length() - 1) {
                throw new ConflictException("AWARD_BENEFIT_VERSION_INVALID",
                        "benefitDefinitionVersion must use benefitId@version");
            }
            try {
                long version = Long.parseLong(value.substring(separator + 1));
                if (version < 1) throw new NumberFormatException("non-positive version");
                return new BenefitVersion(value, value.substring(0, separator), version);
            } catch (NumberFormatException invalid) {
                throw new ConflictException("AWARD_BENEFIT_VERSION_INVALID",
                        "benefitDefinitionVersion must use benefitId@positiveVersion");
            }
        }
    }

    public record AssembleCommand(String sourceRequestId, String campaignId, long definitionVersion,
            String subjectRef, String offerToken, String cartDigest) {
        /** 在进入业务事务前收紧内部触发 DTO 的长度和格式边界。 */
        public AssembleCommand {
            sourceRequestId = required(sourceRequestId, "sourceRequestId", 128);
            campaignId = required(campaignId, "campaignId", 64);
            if (definitionVersion < 1 || definitionVersion > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("definitionVersion is invalid");
            }
            subjectRef = required(subjectRef, "subjectRef", 256);
            offerToken = required(offerToken, "offerToken", 32_768);
            if (cartDigest == null || !cartDigest.matches("sha256:[a-f0-9]{64}")) {
                throw new IllegalArgumentException("cartDigest must be a sha256 digest");
            }
        }

        private static String required(String value, String name, int maxLength) {
            if (value == null || value.isBlank() || value.length() > maxLength) {
                throw new IllegalArgumentException(name + " is invalid");
            }
            return value.trim();
        }
    }

    public record AssembledIntent(AwardIntent intent, String payload, String payloadHash, String subjectHash) { }

    public enum BenefitType { CASH, COUPON, SERVICE_VOUCHER, REDEMPTION_CODE, PHYSICAL }
    public enum PartialPolicy { BEST_EFFORT }
    public record DecisionReference(String decisionId, String activityId, Integer activityVersion) { }
    public record AwardItemIntent(String clientItemId, String benefitSkuId, BenefitType benefitType,
            Long amountMinor, String currency, long quantity, Map<String, String> metadata) {
        /** 防止调用方在组装后继续修改 metadata。 */
        public AwardItemIntent {
            metadata = Map.copyOf(metadata == null ? Map.of() : metadata);
        }
    }
    public record AwardIntent(String schemaVersion, String sourceSystem, String sourceRequestId,
            String sourceBusinessNo, String recipientRef, DecisionReference decision,
            PartialPolicy partialPolicy, List<AwardItemIntent> items, Map<String, String> trace) {
        /** 固化出站项与追踪字段，保证持久化 payload 不受后续修改影响。 */
        public AwardIntent {
            items = List.copyOf(items);
            trace = Map.copyOf(trace);
        }
    }
}
