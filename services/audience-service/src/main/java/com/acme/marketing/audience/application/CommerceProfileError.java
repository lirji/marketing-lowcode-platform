package com.acme.marketing.audience.application;

import com.acme.marketing.platform.error.ConflictException;
import com.acme.marketing.platform.error.DependencyUnavailableException;

/** 商城画像公开错误使用固定代码，控制台可直接展示处理提示。 */
public enum CommerceProfileError {
    SOURCE_DISABLED("AUDIENCE_PROFILE_SOURCE_DISABLED", "当前租户尚未启用商城会员画像"),
    SOURCE_LIMIT("AUDIENCE_PROFILE_SOURCE_LIMIT", "商城会员数量超过同步圈选上限"),
    SOURCE_UNAVAILABLE("AUDIENCE_PROFILE_SOURCE_UNAVAILABLE", "商城会员画像暂不可用，请稍后重试"),
    FIELD_UNSUPPORTED("AUDIENCE_PROFILE_FIELD_UNSUPPORTED", "商城画像当前只支持会员等级字段"),
    SNAPSHOT_IMMUTABLE("AUDIENCE_PROFILE_SNAPSHOT_IMMUTABLE", "商城画像快照只允许重新生成");

    private final String code;
    private final String message;

    CommerceProfileError(String code, String message) {
        this.code = code;
        this.message = message;
    }

    /** 用现有领域异常保持错误响应契约。 */
    public ConflictException conflict() {
        return new ConflictException(code, message);
    }

    /** 用现有可重试异常映射上游不可用。 */
    public DependencyUnavailableException unavailable() {
        return new DependencyUnavailableException(code, message);
    }
}
