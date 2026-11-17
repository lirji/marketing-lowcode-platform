package com.acme.marketing.control.interfaces;

import com.acme.marketing.control.application.ControlApplicationService;
import com.acme.marketing.control.application.JourneySimulationErrorCode;
import com.acme.marketing.control.application.JourneySimulationRequest;
import com.acme.marketing.control.application.JourneySimulationService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

/** 专属仿真协议避免把Journey状态误读成价格结果；请求体解析前有界。 */
@RestController
public final class JourneySimulationController {
    private static final int MAX_REQUEST_BYTES = 64 * 1024;
    private final ControlApplicationService service;
    private final ObjectMapper mapper;
    public JourneySimulationController(ControlApplicationService service, ObjectMapper mapper) {
        this.service = service; this.mapper = mapper;
    }

    /** 授权先行、严格JSON、同步纯计算；重试不会生成真实命令。 */
    @PostMapping(path = "/api/v1/definitions/{definitionId}/versions/{version}:simulate-journey", consumes = MediaType.APPLICATION_JSON_VALUE)
    public JourneySimulationService.Result simulate(@PathVariable String definitionId, @PathVariable long version, HttpServletRequest request) {
        service.authorizeJourneySimulation(definitionId, version);
        if (request.getContentLengthLong() > MAX_REQUEST_BYTES) throw JourneySimulationErrorCode.REQUEST_TOO_LARGE.invalid();
        try {
            byte[] body = request.getInputStream().readNBytes(MAX_REQUEST_BYTES + 1);
            if (body.length > MAX_REQUEST_BYTES) throw JourneySimulationErrorCode.REQUEST_TOO_LARGE.invalid();
            var json = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readTree(body);
            return service.simulateJourney(definitionId, version, JourneySimulationRequest.read(json));
        } catch (IOException | JacksonException invalid) { throw JourneySimulationErrorCode.INPUT_INVALID.invalid(); }
    }
}
