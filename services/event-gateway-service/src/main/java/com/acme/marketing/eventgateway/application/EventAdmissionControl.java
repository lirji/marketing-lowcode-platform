package com.acme.marketing.eventgateway.application;

/**
 * 事件接入容量门禁。
 *
 * <p>应用服务只依赖该端口判断当前是否允许继续写入，不感知 backlog 的存储与采集方式。
 */
public interface EventAdmissionControl {

    /** 在创建新 outbox 事件前校验当前容量，超过上限时抛出可重试的依赖不可用异常。 */
    void assertWritable(String tenantId);
}
