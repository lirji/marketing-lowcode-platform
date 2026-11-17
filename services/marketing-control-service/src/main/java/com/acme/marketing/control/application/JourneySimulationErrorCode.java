package com.acme.marketing.control.application;

import com.acme.marketing.platform.error.DomainException;

/** 仿真输入和资源预算使用稳定问题码，不把模拟失败当作真实履约失败。 */
public enum JourneySimulationErrorCode {
    INPUT_INVALID("JOURNEY_SIMULATION_INPUT_INVALID", "旅程仿真输入结构、字段类型或时间顺序无效"),
    LIMIT_EXCEEDED("JOURNEY_SIMULATION_LIMIT_EXCEEDED", "仿真最多32个事件、每组32个属性、7天窗口；定义每信号最多1000步、最多100次迭代"),
    REQUEST_TOO_LARGE("JOURNEY_SIMULATION_REQUEST_TOO_LARGE", "旅程仿真正文不能超过64 KiB"),
    DIALECT_REQUIRED("JOURNEY_SIMULATION_DIALECT_REQUIRED", "仅支持Journey定义，请使用对应方言的仿真入口"),
    DEDICATED_ENDPOINT_REQUIRED("JOURNEY_SIMULATION_ENDPOINT_REQUIRED", "Journey请使用专属旅程仿真入口，不支持金额试算");
    private final String code;
    private final String message;
    JourneySimulationErrorCode(String code, String message) { this.code = code; this.message = message; }
    /** 沿用平台400与ProblemDetail格式。 */
    public DomainException invalid() { return new DomainException(code, message); }
}
