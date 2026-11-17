package com.acme.marketing.decision.interfaces;

import com.acme.marketing.contracts.release.ReleaseBundleAck;
import com.acme.marketing.contracts.release.ReleaseBundlePrepareRequest;
import com.acme.marketing.contracts.release.ReleaseBundleProtocolError;
import com.acme.marketing.decision.application.DecisionBundlePreparationService;
import com.acme.marketing.platform.web.TenantContextHolder;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

/** 组合准备是单独协议；先鉴权再有界解析，不接收激活命令或外部制品地址。 */
@RestController
public final class DecisionBundleController {
    private final DecisionBundlePreparationService service;
    private final ObjectMapper mapper;

    /** 只负责有界协议解析，实际可信性与持久化验证由应用层完成。 */
    public DecisionBundleController(DecisionBundlePreparationService service, ObjectMapper mapper) {
        this.service = service; this.mapper = mapper;
    }

    /** 每次重试重新读取持久化成员并签发当前证据，不返回过期缓存回执。 */
    @PostMapping(path = "/api/v1/decisions/runtime/release-bundles:prepare", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ReleaseBundleAck prepare(HttpServletRequest request) {
        TenantContextHolder.requireCurrent().requirePermission("runtime:warm");
        return service.prepare(read(request));
    }

    private ReleaseBundlePrepareRequest read(HttpServletRequest request) {
        if (request.getContentLengthLong() > ReleaseBundlePrepareRequest.MAX_REQUEST_BYTES)
            throw ReleaseBundleProtocolError.REQUEST_INVALID.invalid();
        try {
            byte[] bytes = request.getInputStream().readNBytes(ReleaseBundlePrepareRequest.MAX_REQUEST_BYTES + 1);
            if (bytes.length > ReleaseBundlePrepareRequest.MAX_REQUEST_BYTES)
                throw ReleaseBundleProtocolError.REQUEST_INVALID.invalid();
            ReleaseBundlePrepareRequest value = mapper.readerFor(ReleaseBundlePrepareRequest.class)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(bytes);
            if (value == null) throw ReleaseBundleProtocolError.REQUEST_INVALID.invalid();
            return value;
        } catch (IOException | JacksonException malformed) {
            throw ReleaseBundleProtocolError.REQUEST_INVALID.invalid();
        }
    }
}
