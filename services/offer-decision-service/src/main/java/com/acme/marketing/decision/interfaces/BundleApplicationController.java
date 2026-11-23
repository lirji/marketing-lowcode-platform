package com.acme.marketing.decision.interfaces;

import com.acme.marketing.contracts.release.DecisionExecutionReceipt;
import com.acme.marketing.contracts.release.ReleaseBundleProtocolError;
import com.acme.marketing.decision.application.BundleApplicationService;
import com.acme.marketing.decision.application.DecisionBundleDirectiveService;
import com.acme.marketing.platform.web.TenantContextHolder;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

/** 逐副本本机探测与 cell 级整体应用，两个动作的成功回执具有不同业务语义。 */
@RestController
@RequestMapping("/api/v1/decisions/runtime/release-bundle-applications/{campaignId}/{sequence}")
public final class BundleApplicationController {
    private static final int MAX_REQUEST_BYTES = 4 * 1024 * 1024;
    private final BundleApplicationService applications;
    private final ObjectMapper mapper;
    public BundleApplicationController(BundleApplicationService applications, ObjectMapper mapper) {
        this.applications = applications; this.mapper = mapper;
    }

    /** 探测目标来自本实例配置与受信清单，调用者不能自报实例。 */
    @PostMapping("/attest")
    public DecisionExecutionReceipt attest(@PathVariable String campaignId, @PathVariable long sequence) {
        return applications.attest(campaignId, sequence);
    }

    /** 全量回执数量有界，先鉴权再读取；未知字段与尾随数据视为错误。 */
    @PostMapping(value = "/apply", consumes = MediaType.APPLICATION_JSON_VALUE)
    public BundleApplicationService.Applied apply(@PathVariable String campaignId, @PathVariable long sequence, HttpServletRequest request) {
        TenantContextHolder.requireCurrent().requirePermission(DecisionBundleDirectiveService.RECEIVE_PERMISSION);
        TenantContextHolder.requireCurrent().requirePermission(BundleApplicationService.APPLY_PERMISSION);
        if (request.getContentLengthLong() > MAX_REQUEST_BYTES) throw ReleaseBundleProtocolError.EXECUTION_REQUEST_INVALID.invalid();
        BundleApplicationService.ApplyRequest body;
        try {
            var bytes = request.getInputStream().readNBytes(MAX_REQUEST_BYTES + 1);
            if (bytes.length > MAX_REQUEST_BYTES) throw ReleaseBundleProtocolError.EXECUTION_REQUEST_INVALID.invalid();
            body = mapper.readerFor(BundleApplicationService.ApplyRequest.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(bytes);
            if (body == null) throw ReleaseBundleProtocolError.EXECUTION_REQUEST_INVALID.invalid();
        } catch (IOException | JacksonException malformed) { throw ReleaseBundleProtocolError.EXECUTION_REQUEST_INVALID.invalid(); }
        return applications.apply(campaignId, sequence, body);
    }

    /** 查询原命令，恢复通信超时的已提交结果。 */
    @GetMapping
    public BundleApplicationService.Applied get(@PathVariable String campaignId, @PathVariable long sequence,
            @RequestParam(required = false) java.time.Instant confirmAbsentAfter) {
        return applications.get(campaignId, sequence, confirmAbsentAfter);
    }
}
