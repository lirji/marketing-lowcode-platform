package com.acme.marketing.control.application;

import com.acme.marketing.platform.error.ConflictException;
import com.acme.marketing.platform.error.DomainException;
import com.acme.marketing.platform.error.NotFoundException;

/** 活动组合的稳定错误目录，沿用平台 ProblemDetail 映射。 */
public enum ReleaseBundleError {
    INPUT_INVALID("RELEASE_BUNDLE_INPUT_INVALID", "组合请求必须包含有效基准版本和2至8个不同的发布清单"),
    REQUEST_TOO_LARGE("RELEASE_BUNDLE_REQUEST_TOO_LARGE", "组合请求或冻结内容超过允许大小"),
    NOT_FOUND("RELEASE_BUNDLE_NOT_FOUND", "该活动尚无所请求的发布组合版本"),
    VERSION_CONFLICT("RELEASE_BUNDLE_VERSION_CONFLICT", "活动组合已有新版本，请重新读取后提交"),
    MEMBER_UNAVAILABLE("RELEASE_BUNDLE_MEMBER_UNAVAILABLE", "组合成员必须是未过期、已审批且未激活的受支持发布清单"),
    MEMBER_CAMPAIGN_MISMATCH("RELEASE_BUNDLE_CAMPAIGN_MISMATCH", "组合成员必须属于当前活动"),
    MEMBER_SCOPE_MISMATCH("RELEASE_BUNDLE_SCOPE_MISMATCH", "组合成员必须属于同一环境和运行区域"),
    DUPLICATE_SLOT("RELEASE_BUNDLE_DUPLICATE_SLOT", "同一组合不能包含同一发布位置的多个版本"),
    MEMBER_ALREADY_BOUND("RELEASE_BUNDLE_MEMBER_ALREADY_BOUND", "发布清单已归属其他不可变组合，请重新暂存清单"),
    REQUIRED("RELEASE_BUNDLE_REQUIRED", "该清单属于活动组合，必须通过完整组合执行发布或回滚");

    private final String code;
    private final String message;
    ReleaseBundleError(String code, String message) { this.code = code; this.message = message; }
    /** 输入错误不改变平台既有400合同。 */
    public DomainException invalid() { return new DomainException(code, message); }
    /** 状态竞争由调用者重新读取业务状态，不隐式重放新命令。 */
    public ConflictException conflict() { return new ConflictException(code, message); }
    /** 精确查询不暴露其他租户的资源存在性。 */
    public NotFoundException notFound() { return new NotFoundException(code, message); }
}
