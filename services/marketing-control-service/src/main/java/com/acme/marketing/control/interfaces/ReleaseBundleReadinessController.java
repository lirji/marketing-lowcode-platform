package com.acme.marketing.control.interfaces;

import com.acme.marketing.contracts.release.ReleaseBundleAck;
import com.acme.marketing.control.application.ReleaseBundleError;
import com.acme.marketing.control.application.ReleaseBundleReadinessService;
import com.acme.marketing.control.application.ReleaseBundleReadinessService.BundleReadinessView;
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

/** 组合证据与旧单清单ACK分开接入，防止旧协议被误算为完整组合能力。 */
@RestController
@RequestMapping("/api/v1/campaigns/{campaignId}/release-bundles/versions/{version}")
public final class ReleaseBundleReadinessController {
    private final ReleaseBundleReadinessService service;
    private final ObjectMapper mapper;

    /** 复用控制面事务服务；接口只做权限前置和有界协议解析。 */
    public ReleaseBundleReadinessController(ReleaseBundleReadinessService service, ObjectMapper mapper) {
        this.service = service; this.mapper = mapper;
    }

    /** 重复或乱序证据返回当前真实准备状态，不生成激活副作用。 */
    @PostMapping(path = "/acks", consumes = MediaType.APPLICATION_JSON_VALUE)
    public BundleReadinessView acknowledge(@PathVariable String campaignId, @PathVariable long version,
            HttpServletRequest request) {
        service.authorizeAck(campaignId);
        return service.acknowledge(campaignId, version, read(request));
    }

    /** 当前时点的运行准备条件可到期失效，客户端不得据旧响应自动激活。 */
    @GetMapping("/readiness")
    public BundleReadinessView readiness(@PathVariable String campaignId, @PathVariable long version) {
        return service.readiness(campaignId, version);
    }

    private ReleaseBundleAck read(HttpServletRequest request) {
        if (request.getContentLengthLong() > ReleaseBundleReadinessService.MAX_ACK_BYTES)
            throw ReleaseBundleError.REQUEST_TOO_LARGE.invalid();
        try {
            byte[] bytes = request.getInputStream().readNBytes(ReleaseBundleReadinessService.MAX_ACK_BYTES + 1);
            if (bytes.length > ReleaseBundleReadinessService.MAX_ACK_BYTES) throw ReleaseBundleError.REQUEST_TOO_LARGE.invalid();
            ReleaseBundleAck result = mapper.readerFor(ReleaseBundleAck.class)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(bytes);
            if (result == null) throw ReleaseBundleError.ACK_INVALID.invalid();
            return result;
        } catch (IOException | JacksonException malformed) { throw ReleaseBundleError.ACK_INVALID.invalid(); }
    }
}
