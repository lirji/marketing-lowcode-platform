package com.acme.marketing.contracts.release;

import com.acme.marketing.platform.error.ConflictException;
import com.acme.marketing.platform.error.DomainException;

/** 组合运行协议的稳定错误目录，供控制面和运行端共用。 */
public enum ReleaseBundleProtocolError {
    REQUEST_INVALID("RELEASE_BUNDLE_PREPARE_INVALID", "组合准备请求格式无效或超过允许大小"),
    PROOF_INVALID("RELEASE_BUNDLE_PROOF_INVALID", "组合签名、租户或有效期校验失败"),
    ACTIVATION_REQUEST_INVALID("RELEASE_BUNDLE_ACTIVATION_REQUEST_INVALID", "组合激活授权请求格式无效或超过允许大小"),
    ACTIVATION_SEQUENCE_CONFLICT("RELEASE_BUNDLE_ACTIVATION_SEQUENCE_CONFLICT", "组合激活序号或指令编号已绑定其他内容"),
    ACTIVATION_SEQUENCE_STALE("RELEASE_BUNDLE_ACTIVATION_SEQUENCE_STALE", "组合激活序号已过时且不存在可重放记录"),
    ACTIVATION_NOT_RECORDED("RELEASE_BUNDLE_ACTIVATION_NOT_RECORDED", "尚未接收该活动的组合激活授权"),
    ACTIVATION_STORED_INVALID("RELEASE_BUNDLE_ACTIVATION_STORED_INVALID", "持久化组合激活授权与索引不一致"),
    ACTIVATION_INVALID("RELEASE_BUNDLE_ACTIVATION_INVALID", "组合激活授权的结构、签名、租户或有效期无效"),
    EXECUTION_SIGNING_UNAVAILABLE("RELEASE_BUNDLE_EXECUTION_SIGNING_UNAVAILABLE", "Journey执行签名未配置或不可用"),
    EXECUTION_ARTIFACT_INVALID("RELEASE_BUNDLE_EXECUTION_ARTIFACT_INVALID", "Journey实际提交JAR不可验证"),
    EXECUTION_PROBE_UNAVAILABLE("RELEASE_BUNDLE_EXECUTION_PROBE_UNAVAILABLE", "Journey执行探针未通过，请按持久发布流程重试"),
    EXECUTION_PROBE_INVALID("RELEASE_BUNDLE_EXECUTION_PROBE_INVALID", "执行探针的目标、签名、来源或有效期无效"),
    EXECUTION_ACK_INVALID("RELEASE_BUNDLE_EXECUTION_ACK_INVALID", "执行回执与探针、实际任务或可信部署身份不一致"),
    ADMISSION_REQUEST_INVALID("RELEASE_BUNDLE_ADMISSION_REQUEST_INVALID", "组合准入请求格式无效或超过允许大小"),
    ADMISSION_NOT_APPLIED("RELEASE_BUNDLE_ADMISSION_NOT_APPLIED", "该活动尚无可用于新参与的实际应用组合"),
    PARTICIPATION_SUBJECT_CONFLICT("RELEASE_BUNDLE_PARTICIPATION_SUBJECT_CONFLICT", "该参与编号已绑定其他主体"),
    PARTICIPATION_STORED_INVALID("RELEASE_BUNDLE_PARTICIPATION_STORED_INVALID", "持久化参与事实与索引或来源不一致"),
    PARTICIPATION_SIGNING_UNAVAILABLE("RELEASE_BUNDLE_PARTICIPATION_SIGNING_UNAVAILABLE", "参与签发密钥尚未配置完整或不受当前参与信任配置认可"),
    PARTICIPATION_INVALID("RELEASE_BUNDLE_PARTICIPATION_INVALID", "参与版本凭证的结构、签名、主体、范围或来源无效"),
    PARTICIPATION_CONSUMED("RELEASE_BUNDLE_PARTICIPATION_CONSUMED", "该组合参与已结束或过期，不能重新创建执行实例"),
    SLOT_MANAGED("RELEASE_BUNDLE_SLOT_MANAGED", "该运行位置已由活动组合管理，不能单独激活或回滚成员"),
    MEMBER_NOT_INSTALLED("RELEASE_BUNDLE_MEMBER_NOT_INSTALLED", "组合成员尚未安装到当前运行位置"),
    MEMBER_MISMATCH("RELEASE_BUNDLE_MEMBER_MISMATCH", "持久化成员与签名组合不一致");

    private final String code;
    private final String message;
    ReleaseBundleProtocolError(String code, String message) { this.code = code; this.message = message; }
    /** 契约构造与JSON解析共用同一错误文案。 */
    public String message() { return message; }
    /** 输入错误沿用平台400问题响应。 */
    public DomainException invalid() { return new DomainException(code, message); }
    /** 历史或接收事实不存在时保持平台404语义。 */
    public com.acme.marketing.platform.error.NotFoundException notFound() { return new com.acme.marketing.platform.error.NotFoundException(code, message); }
    /** 准备条件不成立时拒绝，不回退到最新单清单。 */
    public ConflictException conflict() { return new ConflictException(code, message); }
}
