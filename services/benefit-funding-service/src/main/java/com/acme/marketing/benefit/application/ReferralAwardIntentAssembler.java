package com.acme.marketing.benefit.application;

import com.acme.marketing.contracts.referral.ReferralAwardAuthorizationCodec;
import com.acme.marketing.contracts.referral.ReferralAwardIdentity;
import com.acme.marketing.platform.crypto.Digests;
import com.acme.marketing.platform.error.ConflictException;
import com.acme.marketing.platform.identity.TenantScope;
import com.acme.marketing.referral.ReferralPolicyValidator;
import com.acme.marketing.referral.ReferralRewardRule;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/** 裂变专用候选组装器：签名与历史证明不能代替在线资格消费、风控及耐久受理。 */
public final class ReferralAwardIntentAssembler {
    public static final String SOURCE_SYSTEM="marketing-referral";
    private final ReferralAwardTrustPort trust;
    private final ReferralAwardProofPort proofs;
    private final AwardDispatchModeRouter modes;
    private final Clock clock;
    private final ObjectMapper json;

    /** 仅注入可信端口，不接受请求自带key、SKU、数量、主体或现金字段。 */
    public ReferralAwardIntentAssembler(ReferralAwardTrustPort trust,ReferralAwardProofPort proofs,
            AwardDispatchModeRouter modes,Clock clock,ObjectMapper json) {
        this.trust=Objects.requireNonNull(trust);this.proofs=Objects.requireNonNull(proofs);
        this.modes=Objects.requireNonNull(modes);this.clock=Objects.requireNonNull(clock);this.json=Objects.requireNonNull(json);
    }

    /** scope必须来自已认证机器上下文；无HTTP入口，所有证明读取实际在事务外。 */
    public Candidate assembleCandidate(TenantScope scope,String organizationId,String shopId,String sourceRequestId,String token) {
        return assembleValidatedCandidate(scope,organizationId,shopId,sourceRequestId,token).candidate();
    }

    /** 内部受理保留已验证claims上下文；不允许从未验签token构造永久身份。 */
    public ValidatedCandidate assembleValidatedCandidate(TenantScope scope,String organizationId,String shopId,String sourceRequestId,String token) {
        if(TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable();
        Objects.requireNonNull(scope);scope.requirePermission("referral:award");
        scope.requireOrganization(organizationId);scope.requireShop(shopId);
        try {
            String tenant=scope.tenantId().value();
            require(modes.modeFor(tenant)==AwardDispatchModeRouter.DeliveryMode.CENTER);
            var configured=trust.forTenant(tenant);require(configured!=null);
            var expected=new ReferralAwardAuthorizationCodec.Expected(configured.issuer(),tenant,organizationId,shopId,configured.maxTokenLifetime());
            var verified=ReferralAwardAuthorizationCodec.verify(token,configured.keys()::get,expected,clock.instant());
            var claims=verified.claims();
            require(claims.sourceRequestId().equals(sourceRequestId)
                    && sourceRequestId.equals(ReferralAwardIdentity.sourceRequestId(tenant,claims.rewardId())));
            var proof=proofs.resolve(verified);require(proof!=null);
            var released=proof.release();var catalog=proof.catalog();require(released!=null && catalog!=null);
            require(tenant.equals(released.tenantId()) && organizationId.equals(released.organizationId()) && shopId.equals(released.shopId())
                    && claims.campaignId().equals(released.campaignId()) && claims.definitionId().equals(released.definitionId())
                    && claims.definitionVersion()==released.definitionVersion() && claims.generation()==released.generation()
                    && claims.artifactId().equals(released.artifactId()) && claims.artifactHash().equals(released.artifactHash())
                    && "marketing-referral-plan/1".equals(released.abi())
                    && verified.stableDigest().equals(proof.stableClaimsDigest()));
            ReferralPolicyValidator.validate(released.plan());
            var matching=released.plan().rewards().stream().filter(r->claims.ruleId().equals(r.ruleId())).toList();
            require(matching.size()==1);var rule=matching.getFirst();
            require(rule.role().name().equals(claims.role().name()) && rule.quantity()==claims.quantity() && rule.quantity()==1);
            require(rule.mode()==ReferralRewardRule.Mode.PER_RELATION
                    ? claims.relationId()!=null && claims.milestone()==null
                    : claims.relationId()==null && claims.milestone()!=null && claims.milestone()==rule.threshold());
            require(tenant.equals(catalog.tenantId()) && rule.benefitDefinitionVersion().equals(catalog.benefitReference())
                    && rule.skuVersion().equals(catalog.skuReference()) && text(catalog.benefitId(),128)
                    && catalog.benefitVersion()>0 && text(catalog.skuId(),128) && catalog.skuVersion()>=0
                    && "COUPON".equals(catalog.benefitType()));
            // 证明读取可能耗时，必须用原始精度复检，不将数据库微秒截断用于安全裁决。
            Instant now=clock.instant();
            ReferralAwardAuthorizationCodec.verifyTime(claims,configured.maxTokenLifetime(),now);
            require(proof.issuedAt()!=null && proof.expiresAt()!=null && !now.isBefore(proof.issuedAt())
                    && now.isBefore(proof.expiresAt()) && proof.issuedAt().isBefore(proof.expiresAt())
                    && Duration.between(proof.issuedAt(),proof.expiresAt()).compareTo(configured.maxProofLifetime())<=0);
            require(modes.modeFor(tenant)==AwardDispatchModeRouter.DeliveryMode.CENTER);
            var item=new Item(claims.rewardId(),catalog.skuId(),"COUPON",null,null,1,Map.of(),catalog.skuVersion());
            var intent=new Intent("1.0",SOURCE_SYSTEM,sourceRequestId,claims.rewardId(),claims.beneficiarySubject(),null,
                    "BEST_EFFORT",List.of(item),Map.of());
            String payload=json.writeValueAsString(intent);
            return new ValidatedCandidate(new Candidate("CANDIDATE_ONLY",verified.stableDigest(),intent,"sha256:"+Digests.sha256Hex(payload)),claims);
        } catch(RuntimeException failure) {
            // 失败不携带令牌、主体、目录地址或可信适配器异常链。
            throw unavailable();
        }
    }

    private static boolean text(String value,int maximum) {
        if(value==null || value.isBlank() || value.codePointCount(0,value.length())>maximum)return false;
        for(int i=0;i<value.length();i++) {
            char c=value.charAt(i);
            if(Character.isHighSurrogate(c)){if(++i>=value.length() || !Character.isLowSurrogate(value.charAt(i)))return false;}
            else if(Character.isLowSurrogate(c))return false;
        }
        return true;
    }
    private static void require(boolean valid){if(!valid)throw unavailable();}
    private static ConflictException unavailable(){return new ConflictException("REFERRAL_AWARD_CANDIDATE_UNAVAILABLE","referral award candidate is unavailable");}

    /** 已验签上下文仅供内部编排，不写明文数据库或普通响应。 */
    public record ValidatedCandidate(Candidate candidate,com.acme.marketing.contracts.referral.ReferralAwardAuthorizationClaims claims) {
        @Override public String toString(){return "ValidatedReferralCandidate[redacted]";}
    }

    /** 新来源专用DTO，避免改变旧drools字段或null序列化语义。 */
    public record Item(String clientItemId,String benefitSkuId,String benefitType,Long amountMinor,String currency,
            long quantity,Map<String,String> metadata,long expectedSkuVersion) {
        public Item{metadata=Map.copyOf(metadata);}
    }
    /** recipient仅用于未来受保护投递，不出现在默认诊断中；本类不执行投递。 */
    public record Intent(String schemaVersion,String sourceSystem,String sourceRequestId,String sourceBusinessNo,
            String recipientRef,Object decision,String partialPolicy,List<Item> items,Map<String,String> trace) {
        public Intent{items=List.copyOf(items);trace=Map.copyOf(trace);}
        @Override public String toString(){return "ReferralCandidateIntent[redacted]";}
    }
    /** 名称与状态明确表明尚无资格确认receipt、风控放行或持久成功。 */
    public record Candidate(String status,String stableClaimsDigest,Intent intent,String payloadHash) {
        @Override public String toString(){return "ReferralAwardCandidate[redacted]";}
    }
}
