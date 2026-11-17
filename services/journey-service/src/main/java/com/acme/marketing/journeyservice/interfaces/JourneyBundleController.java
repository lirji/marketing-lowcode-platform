package com.acme.marketing.journeyservice.interfaces;

import com.acme.marketing.contracts.release.ReleaseBundleAck;
import com.acme.marketing.contracts.release.ReleaseBundlePrepareRequest;
import com.acme.marketing.contracts.release.ReleaseBundleProtocolError;
import com.acme.marketing.journeyservice.application.JourneyRuntimeReleaseService;
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
public final class JourneyBundleController {
    private final JourneyRuntimeReleaseService service;
    private final ObjectMapper mapper;

    /** 复用原Journey发布服务的制品与定义校验。 */
    public JourneyBundleController(JourneyRuntimeReleaseService service, ObjectMapper mapper) {
        this.service = service; this.mapper = mapper;
    }

    /** 每次重试重新读取持久化成员并签发当前证据，不返回过期缓存回执。 */
    @PostMapping(path = "/api/v1/journey-runtime/release-bundles:prepare", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ReleaseBundleAck prepare(HttpServletRequest request) {
        TenantContextHolder.requireCurrent().requirePermission("runtime:warm");
        return service.prepareBundle(read(request));
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
