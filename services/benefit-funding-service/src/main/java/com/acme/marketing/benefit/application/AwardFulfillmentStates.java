package com.acme.marketing.benefit.application;

import java.util.Arrays;

/** 履约观察的协议状态；未知上游状态拒绝写入，不能猜测为成功。 */
public final class AwardFulfillmentStates {
    private AwardFulfillmentStates() { }
    /** 权益中心订单状态，可能因后续补偿或撤销改变。 */
    public enum OrderStatus {
        ACCEPTED("ACCEPTED"), PROCESSING("PROCESSING"), SUCCEEDED("SUCCEEDED"), PARTIAL_SUCCEEDED("PARTIAL_SUCCEEDED"), FAILED("FAILED"), REMEDIATING("REMEDIATING"), REVERSING("REVERSING"), REVERSED("REVERSED"), PARTIALLY_REVERSED("PARTIALLY_REVERSED");
        private final String code;
        OrderStatus(String code) { this.code = code; }
        /** 显式稳定协议值。 */
        public String code() { return code; }
        /** 未知状态保持失败关闭。 */
        public static boolean accepts(String value) { return Arrays.stream(values()).anyMatch(state -> state.code.equals(value)); }
    }
    /** 权益中心项状态，与订单汇总状态分开。 */
    public enum ItemStatus {
        PENDING("PENDING"), RESERVED("RESERVED"), DISPATCHING("DISPATCHING"), SUCCEEDED("SUCCEEDED"), FAILED_FINAL("FAILED_FINAL"), UNKNOWN("UNKNOWN"), QUERYING("QUERYING"), REISSUING("REISSUING"), REVERSING("REVERSING"), REVERSED("REVERSED"), REVERSAL_FAILED("REVERSAL_FAILED"), REVERSAL_UNKNOWN("REVERSAL_UNKNOWN");
        private final String code;
        ItemStatus(String code) { this.code = code; }
        /** 显式稳定协议值。 */
        public String code() { return code; }
        /** 未知状态保持失败关闭。 */
        public static boolean accepts(String value) { return Arrays.stream(values()).anyMatch(state -> state.code.equals(value)); }
    }
    /** 本地查询任务状态，不代表到账结果。 */
    public enum SyncStatus {
        UNOBSERVED("UNOBSERVED"), PENDING("PENDING"), RUNNING("RUNNING"), PAUSED("PAUSED");
        private final String code;
        SyncStatus(String code) { this.code = code; }
        /** 显式稳定协议值。 */
        public String code() { return code; }
        /** 未知状态保持失败关闭。 */
        public static boolean accepts(String value) { return Arrays.stream(values()).anyMatch(state -> state.code.equals(value)); }
    }
}
