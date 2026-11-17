package com.acme.marketing.contracts.release;

import java.time.Instant;

/** 控制面授权整个组合切换；运行面必须另行持久应用，不能把此对象当作生效回执。 */
public record ReleaseBundleActivationDirective(String directiveId, ReleaseBundleManifest bundle,
        long activationSequence, String action, String organizationId, String shopId,
        Instant authorizedAt, String authorizedBy, String signatureKeyId, String signature) {
    public ReleaseBundleActivationDirective {
        if (!identifier(directiveId, 64) || bundle == null || bundle.signature().isBlank()
                || activationSequence < 1 || action == null || !scopeId(organizationId)
                || (shopId == null || (!shopId.isEmpty() && !scopeId(shopId)))
                || authorizedAt == null || !bounded(authorizedBy, 128) || !identifier(signatureKeyId, 64))
            throw invalid();
        if (authorizedAt.isBefore(bundle.createdAt()) || !authorizedAt.isBefore(bundle.expiresAt())
                || bundle.members().stream().anyMatch(member -> member.activationAt().isAfter(authorizedAt)))
            throw invalid();
        Action.fromCode(action);
        signature = signature == null ? "" : signature;
    }

    private static boolean bounded(String value, int maximum) {
        return value != null && !value.isBlank() && value.length() <= maximum
                && value.chars().noneMatch(Character::isISOControl);
    }

    /** 沿用现有活动ID字符集合，只有通配符不能成为签名资源归属。 */
    private static boolean scopeId(String value) { return bounded(value, 64) && !"*".equals(value); }

    private static boolean identifier(String value, int maximum) {
        return value != null && value.length() <= maximum && value.matches("[A-Za-z0-9_.:@-]+");
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(ReleaseBundleProtocolError.ACTIVATION_INVALID.message());
    }

    /** 回滚仍使用新的激活序号；显式code不随枚举名称重构变化。 */
    public enum Action {
        ACTIVATE("ACTIVATE"), ROLLBACK("ROLLBACK");
        private final String code;
        Action(String code) { this.code = code; }
        public String code() { return code; }
        public static Action fromCode(String code) {
            for (Action action : values()) if (action.code.equals(code)) return action;
            throw invalid();
        }
    }
}
