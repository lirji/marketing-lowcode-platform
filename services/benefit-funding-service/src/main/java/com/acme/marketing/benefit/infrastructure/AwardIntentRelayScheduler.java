package com.acme.marketing.benefit.infrastructure;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 在独立调度周期内触发有界 AwardIntent 投递，避免阻塞其它 benefit outbox。 */
@Component
@ConditionalOnProperty(name = "marketing.award.relay-enabled", havingValue = "true")
public final class AwardIntentRelayScheduler {
    private final AwardIntentRelay relay;

    /** 构造仅在 Relay 开关开启时注册的调度适配器。 */
    public AwardIntentRelayScheduler(AwardIntentRelay relay) {
        this.relay = relay;
    }

    /** 执行一次中继扫描；单次批量由 relay 自身配置限制。 */
    @Scheduled(fixedDelayString = "${marketing.award.relay-delay-ms:5000}",
            initialDelayString = "${marketing.award.relay-initial-delay-ms:5000}")
    public void relay() {
        relay.relayOnce();
    }
}
