package com.acme.marketing.decision.interfaces;

import com.acme.marketing.contracts.release.ReleaseBundleActivationDirective;
import com.acme.marketing.contracts.release.ReleaseBundleProtocolError;
import com.acme.marketing.decision.application.DecisionBundleDirectiveService;
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

/** 运行端授权接收与查询；先鉴权再有界解析，不提供控制面签发能力。 */
@RestController
@RequestMapping("/api/v1/decisions/runtime/release-bundle-directives")
public final class DecisionBundleDirectiveController {
    private final DecisionBundleDirectiveService service;
    private final ObjectMapper mapper;
    /** 协议边界复用平台权限和问题响应。 */
    public DecisionBundleDirectiveController(DecisionBundleDirectiveService service, ObjectMapper mapper) {
        this.service = service; this.mapper = mapper;
    }
    /** 返回持久接收事实，不修改旧在线运行指针。 */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public DecisionBundleDirectiveService.Receipt record(HttpServletRequest request) {
        TenantContextHolder.requireCurrent().requirePermission(DecisionBundleDirectiveService.RECEIVE_PERMISSION);
        return service.record(read(request));
    }
    /** 超时重试可查询同一活动的最新持久接收事实。 */
    @GetMapping("/{campaignId}/latest")
    public DecisionBundleDirectiveService.Receipt latest(@PathVariable String campaignId) { return service.latest(campaignId); }
    private ReleaseBundleActivationDirective read(HttpServletRequest request) {
        if (request.getContentLengthLong() > DecisionBundleDirectiveService.MAX_REQUEST_BYTES)
            throw ReleaseBundleProtocolError.ACTIVATION_REQUEST_INVALID.invalid();
        try {
            byte[] content = request.getInputStream().readNBytes(DecisionBundleDirectiveService.MAX_REQUEST_BYTES + 1);
            if (content.length > DecisionBundleDirectiveService.MAX_REQUEST_BYTES) throw ReleaseBundleProtocolError.ACTIVATION_REQUEST_INVALID.invalid();
            ReleaseBundleActivationDirective value = mapper.readerFor(ReleaseBundleActivationDirective.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(content);
            if (value == null) throw ReleaseBundleProtocolError.ACTIVATION_REQUEST_INVALID.invalid();
            return value;
        } catch (IOException | JacksonException malformed) { throw ReleaseBundleProtocolError.ACTIVATION_REQUEST_INVALID.invalid(); }
    }
}
