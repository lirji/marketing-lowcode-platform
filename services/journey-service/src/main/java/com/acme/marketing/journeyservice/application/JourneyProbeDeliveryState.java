package com.acme.marketing.journeyservice.application;

/** 仅描述可靠消息投递进度，不能把SENT或COVERED映射为活动APPLIED。 */
public enum JourneyProbeDeliveryState {
    QUEUED("QUEUED"), RETRY("RETRY"), SENT("SENT"), DEAD("DEAD"), EXPIRED("EXPIRED"), COVERED("COVERED");
    private final String code;
    JourneyProbeDeliveryState(String code) { this.code = code; }
    /** 显式code持久化，不依赖ordinal或展示文案。 */
    public String code() { return code; }
}
