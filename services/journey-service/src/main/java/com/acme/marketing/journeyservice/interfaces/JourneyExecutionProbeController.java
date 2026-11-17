package com.acme.marketing.journeyservice.interfaces;

import com.acme.marketing.contracts.release.JourneyBundleExecutionProbe;
import com.acme.marketing.contracts.release.ReleaseBundleProtocolError;
import com.acme.marketing.journeyservice.application.JourneyExecutionProbeService;
import com.acme.marketing.journeyservice.application.JourneyProbeDeliveryService;
import com.acme.marketing.platform.web.TenantContextHolder;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

/** 内部机器登记/查询入口，先鉴权再有界读取签名挑战；没有在线发布副作用。 */
@RestController
@RequestMapping("/api/v1/journey-runtime/execution-probes")
public final class JourneyExecutionProbeController {
    private final JourneyExecutionProbeService service;
    private final ObjectMapper mapper;
    private final JourneyProbeDeliveryService deliveries;
    /** 复用唯一平台权限与错误响应机制。 */
    public JourneyExecutionProbeController(JourneyExecutionProbeService service, ObjectMapper mapper, JourneyProbeDeliveryService deliveries) { this.service = service; this.mapper = mapper; this.deliveries = deliveries; }
    /** 返回持久登记状态，本片不自动向Kafka发送探针。 */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public JourneyExecutionProbeService.Receipt register(HttpServletRequest request) {
        TenantContextHolder.requireCurrent().requirePermission(JourneyExecutionProbeService.PERMISSION);
        try {
            int max = JourneyExecutionProbeService.MAX_PROBE_BYTES;
            if (request.getContentLengthLong() > max) throw ReleaseBundleProtocolError.EXECUTION_REQUEST_INVALID.invalid();
            byte[] bytes = request.getInputStream().readNBytes(max + 1);
            if (bytes.length > max) throw ReleaseBundleProtocolError.EXECUTION_REQUEST_INVALID.invalid();
            JourneyBundleExecutionProbe value = mapper.readerFor(JourneyBundleExecutionProbe.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .without(DeserializationFeature.ACCEPT_FLOAT_AS_INT).readValue(bytes);
            if (value == null) throw ReleaseBundleProtocolError.EXECUTION_REQUEST_INVALID.invalid();
            return service.register(value);
        } catch (IOException | JacksonException invalid) { throw ReleaseBundleProtocolError.EXECUTION_REQUEST_INVALID.invalid(); }
    }
    /** 显式派发才持久排队，重复调用恢复原任务；请求不能指定topic或目标部署。 */
    @PostMapping("/{campaignId}/{probeId}:dispatch")
    public JourneyProbeDeliveryService.View dispatch(@PathVariable String campaignId, @PathVariable String probeId) { return deliveries.dispatch(campaignId, probeId); }
    /** 查询真实投递进度，SENT与COVERED均不是整体发布成功。 */
    @GetMapping("/{campaignId}/{probeId}/dispatch")
    public JourneyProbeDeliveryService.View delivery(@PathVariable String campaignId, @PathVariable String probeId) { return deliveries.get(campaignId, probeId); }
    /** 查询当前覆盖集合；调用权限和原组织/店铺范围每次重验。 */
    @GetMapping("/{campaignId}/{probeId}")
    public JourneyExecutionProbeService.Receipt get(@PathVariable String campaignId, @PathVariable String probeId) { return service.get(campaignId, probeId); }
}
