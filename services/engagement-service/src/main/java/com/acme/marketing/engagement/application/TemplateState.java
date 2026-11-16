package com.acme.marketing.engagement.application;

/** 模板已支持的业务状态；未识别的存储状态不得获得发送许可。 */
public enum TemplateState {
    APPROVED("APPROVED");

    private final String code;

    TemplateState(String code) {
        this.code = code;
    }

    /** API 与数据库使用稳定编码，不依赖枚举名称或序号。 */
    public String code() {
        return code;
    }
}
