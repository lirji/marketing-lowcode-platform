package com.acme.marketing.control.interfaces;

import com.acme.marketing.control.application.BundleReleaseOperations;
import com.acme.marketing.control.application.ReleaseBundleError;
import com.acme.marketing.control.application.ReleaseBundleReadinessService.BundleReadinessView;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

/** 发布与回滚共用持久命令；HTTP成功响应的业务状态仍须读取 state。 */
@RestController
@RequestMapping("/api/v1/campaigns/{campaignId}/release-bundles")
public final class BundleReleaseOperationController {
    private static final int MAX_REQUEST_BYTES = 4096;
    private final BundleReleaseOperations operations;
    private final ObjectMapper json;

    public BundleReleaseOperationController(BundleReleaseOperations operations, ObjectMapper json) {
        this.operations = operations; this.json = json;
    }

    /** 只接受版本、动作及已观察的应用序号，不接受客户端自报的成员覆盖或签名。 */
    @PostMapping(path = "/operations", consumes = MediaType.APPLICATION_JSON_VALUE)
    public BundleReleaseOperations.View start(@PathVariable String campaignId, @RequestHeader("Idempotency-Key") String key,
            HttpServletRequest request) {
        if (request.getContentLengthLong() > MAX_REQUEST_BYTES) throw ReleaseBundleError.REQUEST_TOO_LARGE.invalid();
        try {
            byte[] bytes = request.getInputStream().readNBytes(MAX_REQUEST_BYTES + 1);
            if (bytes.length > MAX_REQUEST_BYTES) throw ReleaseBundleError.REQUEST_TOO_LARGE.invalid();
            var body = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readTree(bytes);
            if (body == null || !body.isObject() || body.size() != 3 || !body.path("action").isString()
                    || !body.path("version").isIntegralNumber() || !body.path("version").canConvertToLong()
                    || !body.path("expectedAppliedSequence").isIntegralNumber() || !body.path("expectedAppliedSequence").canConvertToLong())
                throw ReleaseBundleError.OPERATION_INVALID.invalid();
            return operations.start(campaignId, key, new BundleReleaseOperations.Request(body.get("version").asLong(),
                    body.get("action").asString(), body.get("expectedAppliedSequence").asLong()));
        } catch (JacksonException | IOException invalid) { throw ReleaseBundleError.OPERATION_INVALID.invalid(); }
    }

    /** 已提交结果可恢复；查询不会触发新应用。 */
    @GetMapping("/operations/{operationId}")
    public BundleReleaseOperations.View get(@PathVariable String campaignId, @PathVariable String operationId) {
        return operations.get(campaignId, operationId);
    }

    /** 显式重试不变更授权序号、目标版本或原截止时刻。 */
    @PostMapping("/operations/{operationId}:retry")
    public BundleReleaseOperations.View retry(@PathVariable String campaignId, @PathVariable String operationId) {
        return operations.retry(campaignId, operationId);
    }

    /** 全新浏览器按位置读取实际已确认的组合及未完成操作。 */
    @GetMapping("/operations")
    public BundleReleaseOperations.Summary summary(@PathVariable String campaignId, @RequestParam String environment,
            @RequestParam String cell, @RequestParam(required = false) Long version) {
        return operations.summary(campaignId, environment, cell, version);
    }

    /** 收集每个实际目标的签名准备证据，不签发激活意图。 */
    @PostMapping("/versions/{version}:prepare-runtime")
    public BundleReadinessView prepare(@PathVariable String campaignId, @PathVariable long version) {
        return operations.prepare(campaignId, version);
    }
}
