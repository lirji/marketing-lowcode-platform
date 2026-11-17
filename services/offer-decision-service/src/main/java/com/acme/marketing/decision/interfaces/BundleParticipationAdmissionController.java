package com.acme.marketing.decision.interfaces;

import com.acme.marketing.contracts.release.ReleaseBundleAdmissionRequest;
import com.acme.marketing.contracts.release.ReleaseBundleParticipation;
import com.acme.marketing.contracts.release.ReleaseBundleProtocolError;
import com.acme.marketing.decision.application.BundleParticipationAdmissionService;
import com.acme.marketing.platform.web.TenantContextHolder;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

/** 可信业务机器入口；不能替代C端登录与业务会员映射，也没有应用指针写入能力。 */
@RestController
public final class BundleParticipationAdmissionController {
    private final BundleParticipationAdmissionService service;
    private final ObjectMapper mapper;
    /** 先鉴权后有界解析，避免未授权调用触发准入库或签发。 */
    public BundleParticipationAdmissionController(BundleParticipationAdmissionService service, ObjectMapper mapper) {
        this.service = service; this.mapper = mapper;
    }
    /** 同一业务参与键永久重放原事实，不以HTTP重试重新选版本。 */
    @PostMapping(value = "/api/v1/decisions/bundle-participations", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ReleaseBundleParticipation admit(HttpServletRequest request) {
        TenantContextHolder.requireCurrent().requirePermission(BundleParticipationAdmissionService.ADMIT_PERMISSION);
        return service.admit(TenantContextHolder.requireCurrent(), read(request));
    }
    private ReleaseBundleAdmissionRequest read(HttpServletRequest request) {
        int maximum = BundleParticipationAdmissionService.MAX_REQUEST_BYTES;
        if (request.getContentLengthLong() > maximum) throw ReleaseBundleProtocolError.ADMISSION_REQUEST_INVALID.invalid();
        try {
            byte[] content = request.getInputStream().readNBytes(maximum + 1);
            if (content.length > maximum) throw ReleaseBundleProtocolError.ADMISSION_REQUEST_INVALID.invalid();
            ReleaseBundleAdmissionRequest value = mapper.readerFor(ReleaseBundleAdmissionRequest.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(content);
            if (value == null) throw ReleaseBundleProtocolError.ADMISSION_REQUEST_INVALID.invalid();
            return value;
        } catch (IOException | JacksonException malformed) { throw ReleaseBundleProtocolError.ADMISSION_REQUEST_INVALID.invalid(); }
    }
}
