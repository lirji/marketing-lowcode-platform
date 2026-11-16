package com.acme.marketing.compiler.interfaces;

import com.acme.marketing.compiler.application.DmnWorkbenchService;
import com.acme.marketing.compiler.application.DmnWorkbenchErrors;
import com.acme.marketing.decision.rule.DecisionTableAdapter;
import com.acme.marketing.platform.web.TenantContextHolder;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 受租户认证保护的 DMN 计算入口，不接受任意 XML，也不保存、发布或执行权益。 */
@RestController
@RequestMapping("/api/v1/dmn")
public final class DmnWorkbenchController {
    private static final int MAX_REQUEST_BYTES = 512 * 1024;
    private final DmnWorkbenchService service;
    private final ObjectMapper mapper;

    /** 复用严格 JSON 配置；在转换 JSON 树之前限制原始请求大小。 */
    public DmnWorkbenchController(DmnWorkbenchService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    /** definition:compile 权限下分析真实决策表覆盖范围。 */
    @PostMapping(value = "/analyze", consumes = MediaType.APPLICATION_JSON_VALUE)
    public DecisionTableAdapter.Analysis analyze(HttpServletRequest request) {
        TenantContextHolder.requireCurrent().requirePermission("definition:compile");
        return service.analyze(read(request));
    }

    /** definition:simulate 权限下求值，输入类型与列定义严格一致。 */
    @PostMapping(value = "/evaluate", consumes = MediaType.APPLICATION_JSON_VALUE)
    public DecisionTableAdapter.Evaluation evaluate(HttpServletRequest request) {
        TenantContextHolder.requireCurrent().requirePermission("definition:simulate");
        return service.evaluate(read(request));
    }

    private JsonNode read(HttpServletRequest request) {
        if (request.getContentLengthLong() > MAX_REQUEST_BYTES) throw DmnWorkbenchErrors.REQUEST_TOO_LARGE.invalid();
        try {
            // 无 Content-Length 的分块传输同样有界，不能只依赖客户端声明的长度。
            byte[] body = request.getInputStream().readNBytes(MAX_REQUEST_BYTES + 1);
            if (body.length > MAX_REQUEST_BYTES) throw DmnWorkbenchErrors.REQUEST_TOO_LARGE.invalid();
            return mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(body);
        } catch (IOException | JacksonException invalid) {
            throw DmnWorkbenchErrors.REQUEST_INVALID.invalid();
        }
    }
}
