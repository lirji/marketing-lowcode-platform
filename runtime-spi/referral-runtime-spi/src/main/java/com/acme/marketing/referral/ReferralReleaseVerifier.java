package com.acme.marketing.referral;

import com.acme.marketing.contracts.artifact.ArtifactAttestation;
import com.acme.marketing.contracts.release.ArtifactReference;
import com.acme.marketing.contracts.release.ReleaseManifest;
import com.acme.marketing.contracts.release.ReleaseManifestSigner;
import com.acme.marketing.platform.crypto.Digests;
import com.acme.marketing.referral.ReferralPlanCompiler.CompiledReferralPlan;
import java.security.PublicKey;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * 裂变制品的无副作用信任边界。验证签名和冻结规则，不代替审批、SKU 闭包或运行时激活。
 * 信任键只从调用方受控配置注入；制品 URI 不会触发网络读取。
 */
public final class ReferralReleaseVerifier {
    public static final String TYPE = "REFERRAL_PLAN";
    public static final String ABI = "marketing-referral-plan/1";
    private static final int MAX_BYTES = 1_048_576;
    private final Map<String, PublicKey> releaseKeys;
    private final Map<String, PublicKey> compilerKeys;
    private final String environment;
    private final String cell;
    private final String namespace;
    // 与 HTTP 容错解析隔离：签名计划不得通过省略、类型转换或重复字段得到另一套解释。
    private final JsonMapper json = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                    DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                    DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                    DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES,
                    DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .build();

    /** 固定部署槽位；空信任键集合合法但必然拒绝，以支持默认关闭配置。 */
    public ReferralReleaseVerifier(Map<String, PublicKey> releaseKeys, Map<String, PublicKey> compilerKeys,
            String environment, String cell, String namespace) {
        this.releaseKeys = Map.copyOf(releaseKeys);
        this.compilerKeys = Map.copyOf(compilerKeys);
        this.environment = required(environment);
        this.cell = required(cell);
        this.namespace = required(namespace);
    }

    /**
     * 返回只能由本验证器创建的结果。结果只代表制品已验证，不能据此签 READY 或发参与许可。
     * now 来自服务可信时钟，不接受请求时间；不为未来签发时间默许未经批准的时钟偏差。
     */
    public VerifiedArtifact verifyInstallation(String tenantId, String releaseKeyId, ReleaseManifest manifest,
            byte[] payload, Instant now) {
        Objects.requireNonNull(now, "now");
        require(manifest != null && payload != null && payload.length > 0 && payload.length <= MAX_BYTES,
                "REFERRAL_RELEASE_INPUT_INVALID");
        // 先快照再验摘要，防止调用者并发修改数组造成验签内容与解析内容不一致。
        byte[] snapshot = payload.clone();
        PublicKey releaseKey = releaseKeys.get(releaseKeyId);
        require(releaseKey != null && new ReleaseManifestSigner().verify(manifest, releaseKey),
                "REFERRAL_MANIFEST_UNTRUSTED");
        require(manifest.tenantId().value().equals(tenantId)
                && environment.equals(manifest.environment()) && cell.equals(manifest.cell())
                && namespace.equals(manifest.namespace()) && "referral".equals(manifest.runtime()),
                "REFERRAL_RELEASE_SCOPE_INVALID");
        require(!manifest.createdAt().isAfter(now) && manifest.expiresAt().isAfter(now)
                && !manifest.activationAt().isBefore(manifest.createdAt())
                && manifest.activationAt().isBefore(manifest.expiresAt()), "REFERRAL_RELEASE_TIME_INVALID");
        require(manifest.canaryBasisPoints() == 0 && manifest.canaryGenerations().isEmpty(),
                "REFERRAL_CANARY_UNSUPPORTED");
        // 这里只识别当前单计划 ABI；不能忽略未验证的附加制品而声称验证了完整清单。
        require(manifest.artifacts().size() == 1, "REFERRAL_ARTIFACT_CLOSURE_UNSUPPORTED");
        require(!manifest.approvalCaseIds().isEmpty()
                && manifest.approvalCaseIds().stream().allMatch(id -> id != null && !id.isBlank()),
                "REFERRAL_APPROVAL_REFERENCE_REQUIRED");
        ArtifactReference reference = manifest.artifacts().getFirst();
        PublicKey compilerKey = compilerKeys.get(reference.signatureKeyId());
        require(TYPE.equals(reference.type()) && ABI.equals(reference.abi()), "REFERRAL_ARTIFACT_ABI_INVALID");
        require(compilerKey != null && ArtifactAttestation.verify(compilerKey, tenantId, reference),
                "REFERRAL_ARTIFACT_UNTRUSTED");
        require(reference.checksum().equals("sha256:" + Digests.sha256Hex(snapshot)),
                "REFERRAL_ARTIFACT_CHECKSUM_INVALID");
        CompiledReferralPlan plan;
        try {
            plan = json.readValue(snapshot, CompiledReferralPlan.class);
            require(plan != null && reference.definitionId().equals(plan.definitionId()),
                    "REFERRAL_PLAN_DEFINITION_INVALID");
            required(plan.scope().organizationId());
            required(plan.scope().shopId());
            require(plan.binding().attribution() == ReferralPlanCompiler.Attribution.FIRST_VALID_BIND
                    && plan.binding().inviteeScope() == ReferralPlanCompiler.InviteeScope.NEW_CUSTOMER
                    && plan.binding().maxBindAgeSeconds() > 0, "REFERRAL_BINDING_POLICY_INVALID");
            ReferralPolicyValidator.validate(plan.policy());
            plan.policy().endsAt().plusSeconds(plan.binding().maxBindAgeSeconds());
        } catch (RuntimeException invalid) {
            // 解析异常可能包含原始载荷；边界只暴露稳定原因码，不传播用户规则文本。
            throw new IllegalArgumentException("REFERRAL_PLAN_INVALID");
        }
        return new VerifiedArtifact(reference, snapshot, plan);
    }

    private static String required(String value) {
        require(value != null && !value.isBlank(), "REFERRAL_SCOPE_REQUIRED");
        return value;
    }

    private static void require(boolean condition, String code) {
        if (!condition) throw new IllegalArgumentException(code);
    }

    /** 构造器私有，防止下游绕过校验自行制造“已验证”结果；字节始终防御性复制。 */
    public static final class VerifiedArtifact {
        private final ArtifactReference reference;
        private final byte[] payload;
        private final CompiledReferralPlan plan;

        private VerifiedArtifact(ArtifactReference reference, byte[] payload, CompiledReferralPlan plan) {
            this.reference = reference;
            this.payload = payload.clone();
            this.plan = plan;
        }

        /** 签名引用包含定义版本与源摘要，不能以 HTTP 参数替换。 */
        public ArtifactReference reference() { return reference; }
        /** 获取已验摘要的载荷副本，供本地持久化使用。 */
        public byte[] payload() { return payload.clone(); }
        /** 获取已经通过同一份载荷解析和业务校验的不可变规则。 */
        public CompiledReferralPlan plan() { return plan; }
    }
}
