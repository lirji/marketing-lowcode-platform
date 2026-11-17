package com.acme.marketing.contracts.release;

import com.acme.marketing.platform.error.ConflictException;
import com.acme.marketing.platform.error.DomainException;

/** 组合运行协议的稳定错误目录，供控制面和运行端共用。 */
public enum ReleaseBundleProtocolError {
    REQUEST_INVALID("RELEASE_BUNDLE_PREPARE_INVALID", "组合准备请求格式无效或超过允许大小"),
    PROOF_INVALID("RELEASE_BUNDLE_PROOF_INVALID", "组合签名、租户或有效期校验失败"),
    MEMBER_NOT_INSTALLED("RELEASE_BUNDLE_MEMBER_NOT_INSTALLED", "组合成员尚未安装到当前运行位置"),
    MEMBER_MISMATCH("RELEASE_BUNDLE_MEMBER_MISMATCH", "持久化成员与签名组合不一致");

    private final String code;
    private final String message;
    ReleaseBundleProtocolError(String code, String message) { this.code = code; this.message = message; }
    /** 输入错误沿用平台400问题响应。 */
    public DomainException invalid() { return new DomainException(code, message); }
    /** 准备条件不成立时拒绝，不回退到最新单清单。 */
    public ConflictException conflict() { return new ConflictException(code, message); }
}
