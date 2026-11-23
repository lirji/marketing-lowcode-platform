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
    REQUIRED("RELEASE_BUNDLE_REQUIRED", "该清单属于活动组合，必须通过完整组合执行发布或回滚"),
    ACK_INVALID("RELEASE_BUNDLE_ACK_INVALID", "组合就绪证据的签名、成员、时间或容量不符合发布策略"),
    ACK_CONFLICT("RELEASE_BUNDLE_ACK_CONFLICT", "同一运行身份在相同时间提供了不同的就绪证据"),
    ACK_LIMIT("RELEASE_BUNDLE_ACK_LIMIT", "当前组合成员的运行身份数量已达到允许上限"),
    OPERATION_INVALID("RELEASE_BUNDLE_OPERATION_INVALID", "请提供有效的发布动作、组合版本、幂等键及基准序号"),
    OPERATION_NOT_FOUND("RELEASE_BUNDLE_OPERATION_NOT_FOUND", "未找到本活动的组合发布操作"),
    OPERATION_CONFLICT("RELEASE_BUNDLE_OPERATION_CONFLICT", "当前位置已有待确认命令或实际生效版本已经变化"),
    OPERATION_KEY_CONFLICT("RELEASE_BUNDLE_OPERATION_KEY_CONFLICT", "该幂等键已用于不同的发布操作"),
    OPERATION_TARGET_INVALID("RELEASE_BUNDLE_OPERATION_TARGET_INVALID", "目标组合版本不符合发布或回滚前提"),
    OPERATION_EXPIRED("RELEASE_BUNDLE_OPERATION_EXPIRED", "组合发布窗口已经结束"),
    OPERATION_NOT_READY("RELEASE_BUNDLE_OPERATION_NOT_READY", "组合成员的就绪证据尚未覆盖所需副本或容量"),
    OPERATION_KILLED("RELEASE_BUNDLE_OPERATION_KILLED", "组合成员已被紧急停用"),
    RUNTIME_UNAVAILABLE("RELEASE_BUNDLE_RUNTIME_UNAVAILABLE", "运行端暂时无法核实原命令结果"),
    RUNTIME_CONFIG("RELEASE_BUNDLE_RUNTIME_CONFIG", "目标运行位置缺少完整的部署连接配置"),
    RUNTIME_EVIDENCE("RELEASE_BUNDLE_RUNTIME_EVIDENCE", "运行端响应与本次组合授权不匹配"),
    PROBE_PENDING("RELEASE_BUNDLE_PROBE_PENDING", "等待 Journey 作业提交全部子任务的执行回执"),
    CONTENT_INVALID("RELEASE_BUNDLE_CONTENT_INVALID", "持久化组合内容与版本记录不一致");

    private final String code;
    private final String message;
    ReleaseBundleError(String code, String message) { this.code = code; this.message = message; }
    public String code() { return code; }
    /** 输入错误不改变平台既有400合同。 */
    public DomainException invalid() { return new DomainException(code, message); }
    /** 状态竞争由调用者重新读取业务状态，不隐式重放新命令。 */
    public ConflictException conflict() { return new ConflictException(code, message); }
    /** 精确查询不暴露其他租户的资源存在性。 */
    public NotFoundException notFound() { return new NotFoundException(code, message); }
}
