package com.acme.marketing.contracts.event;

import com.acme.marketing.contracts.release.JourneyBundleExecutionProbe;
import com.acme.marketing.contracts.release.ReleaseBundleProtocolError;
import com.acme.marketing.platform.crypto.CanonicalMapCodec;
import com.acme.marketing.platform.crypto.Digests;
import java.util.Map;

/** 独立只读探针消息；扰动键只分散真实子任务，不改变签名授权或证明覆盖率。 */
public record JourneyBundleExecutionProbeMessage(String eventType, JourneyBundleExecutionProbe probe, Integer routingNonce) {
    public static final String EVENT_TYPE = "JOURNEY_BUNDLE_EXECUTION_PROBE";
    public static final int MAX_ROUTING_NONCE = 2047;
    public JourneyBundleExecutionProbeMessage {
        if (!EVENT_TYPE.equals(eventType) || probe == null || probe.signature().isEmpty()
                || routingNonce == null || routingNonce < 0 || routingNonce > MAX_ROUTING_NONCE)
            throw new IllegalArgumentException(ReleaseBundleProtocolError.EXECUTION_PROBE_INVALID.message());
    }
    /** 长度规范编码防止tenant/probeId拼接歧义；不使用业务enrollment键。 */
    public String partitionKey() {
        return "bundle-probe:" + Digests.sha256Hex(CanonicalMapCodec.encode(Map.of(
                "purpose", EVENT_TYPE, "tenant", probe.tenantId().value(), "probeId", probe.probeId(),
                "nonce", Integer.toString(routingNonce))));
    }
}
