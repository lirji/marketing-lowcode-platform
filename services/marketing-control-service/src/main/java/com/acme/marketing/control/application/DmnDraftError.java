package com.acme.marketing.control.application;

import com.acme.marketing.platform.error.DomainException;

/** 草稿存取的稳定输入错误，与引擎语义诊断分别归属。 */
public enum DmnDraftError {
    INPUT_INVALID("DMN_DRAFT_INPUT_INVALID", "草稿请求结构或字段类型无效"),
    LIMIT_EXCEEDED("DMN_DRAFT_LIMIT_EXCEEDED", "草稿限 1–4 输入、1–4 输出和 1–32 规则；字段名 32 字符、单元格 256 字符"),
    REQUEST_TOO_LARGE("DMN_DRAFT_REQUEST_TOO_LARGE", "草稿请求正文不能超过 512 KiB"),
    VERSION_INVALID("DMN_DRAFT_VERSION_INVALID", "基准版本必须是非负整数，读取版本必须为正整数");
    public static final String NOT_FOUND = "DMN_DRAFT_NOT_FOUND";
    public static final String NOT_FOUND_MESSAGE = "该活动尚无所请求的 DMN 草稿版本";
    public static final String VERSION_CONFLICT = "DMN_DRAFT_VERSION_CONFLICT";
    public static final String VERSION_CONFLICT_MESSAGE = "草稿已被更新，请保留当前修改并加载最新版本后再保存";
    private final String code;
    private final String message;
    DmnDraftError(String code, String message) { this.code = code; this.message = message; }
    /** 复用平台 ProblemDetail 的 400 映射，不另建异常体系。 */
    public DomainException invalid() { return new DomainException(code, message); }
}
