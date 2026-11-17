package com.acme.marketing.journeyservice.application;

import com.acme.marketing.contracts.release.JourneyBundleExecutionProbe;

/** 有界外部发送端口；未知结果允许同nonce重发，绝不提前推进持久cursor。 */
public interface JourneyProbeSender {
    /** 完整确认才返回true，超时/中断/部分发送返回false并保留中断标志。 */
    boolean send(String topic, JourneyBundleExecutionProbe probe, int firstNonce, int endExclusive);
}
