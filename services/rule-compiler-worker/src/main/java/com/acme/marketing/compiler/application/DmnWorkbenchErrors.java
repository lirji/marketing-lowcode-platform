package com.acme.marketing.compiler.application;

import com.acme.marketing.platform.error.DomainException;

/** 工作台边界的稳定问题码；沿用平台 ProblemDetail，不暴露解析器内部异常。 */
public enum DmnWorkbenchErrors {
    REQUEST_TOO_LARGE("DMN_REQUEST_TOO_LARGE", "请求正文不能超过 512 KiB"),
    REQUEST_INVALID("DMN_REQUEST_INVALID", "请求必须包含约定的表结构和字符串输入值"),
    TABLE_LIMIT("DMN_TABLE_LIMIT", "决策表必须包含 1–4 个输入、1–4 个输出和 1–32 条规则"),
    NAME_INVALID("DMN_NAME_INVALID", "字段名须为 1–32 位 ASCII 标识符、整体唯一，且不能使用模型名、决策名或保留字"),
    TYPE_INVALID("DMN_TYPE_INVALID", "字段类型仅支持 number、string、boolean"),
    HIT_POLICY_INVALID("DMN_HIT_POLICY_INVALID", "命中策略仅支持 UNIQUE 或 FIRST"),
    RULE_INVALID("DMN_RULE_INVALID", "规则单元格数量必须与输入输出列一致"),
    CELL_INVALID("DMN_CELL_INVALID", "单元格须为不超过 256 字符的受限字面量或输入比较，不能包含任意表达式"),
    NUMBER_INVALID("DMN_NUMBER_INVALID", "数字须为纯十进制，最多 18 位数字和 6 位小数"),
    CONTEXT_INVALID("DMN_CONTEXT_INVALID", "试算输入必须精确覆盖输入字段，且字符串值符合字段类型");

    public static final String BUSY_CODE = "DMN_WORKBENCH_BUSY";
    public static final String BUSY_MESSAGE = "DMN 工作台正在计算，请稍后重试";
    private final String code;
    private final String message;

    DmnWorkbenchErrors(String code, String message) { this.code = code; this.message = message; }

    /** 输入错误统一由既有 DomainException 映射为 400。 */
    public DomainException invalid() { return new DomainException(code, message); }
}
