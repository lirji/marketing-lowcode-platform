package com.acme.marketing.journeyservice.application;

import com.acme.marketing.contracts.release.ReleaseBundleProtocolError;
import com.acme.marketing.platform.identity.TenantId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 显式派发开关与可信目标topic，不接受HTTP提供Kafka目的地。 */
@Component
public final class JourneyProbeDeliverySettings {
    public static final int ROUTES_PER_SUBTASK = 16;
    public static final int BATCH_SIZE = 16;
    public static final int LEASE_SECONDS = 30;
    public static final int MAX_FAILURES = 5;
    public static final int EXPIRE_BATCH_SIZE = 32;
    private final boolean enabled;
    private final String topic;
    /** 启用时必须已有可信部署；关闭时不创建producer或调度线程。 */
    public JourneyProbeDeliverySettings(@Value("${marketing.journey.probe-delivery.enabled:false}") boolean enabled,
            @Value("${marketing.journey.probe-delivery.input-topic:mk.journey.signal.v1}") String topic,
            JourneyExecutionDeployment deployment) {
        if (topic == null || !topic.matches("[A-Za-z0-9_.-]{1,249}") || topic.equals(".") || topic.equals(".."))
            throw new IllegalArgumentException(ReleaseBundleProtocolError.EXECUTION_DELIVERY_CONFIG_INVALID.message());
        if (enabled) deployment.expected(new TenantId("configuration-check"), "configuration-check");
        this.enabled = enabled; this.topic = topic;
    }
    public boolean enabled() { return enabled; }
    public String topic() { return topic; }
}
