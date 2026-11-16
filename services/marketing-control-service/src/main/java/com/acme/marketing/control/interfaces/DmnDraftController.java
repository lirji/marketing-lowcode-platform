package com.acme.marketing.control.interfaces;

import com.acme.marketing.control.application.ControlApplicationService;
import com.acme.marketing.control.application.ControlApplicationService.DmnDraftView;
import com.acme.marketing.control.application.ControlCommandExecutor;
import com.acme.marketing.control.application.DmnDraftError;
import com.acme.marketing.control.application.DmnDraftTable;
import com.acme.marketing.platform.web.TenantContextHolder;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 活动范围内的 DMN 草稿存取，复用控制面幂等事务；计算由编译服务负责。 */
@RestController
@RequestMapping("/api/v1/campaigns/{campaignId}/dmn-drafts")
public final class DmnDraftController {
    private static final int MAX_REQUEST_BYTES = 512 * 1024;
    private static final String SAVE_OPERATION = "dmn.draft.save";
    private final ControlApplicationService service;
    private final ControlCommandExecutor commands;
    private final ObjectMapper mapper;

    /** 数据归属与幂等回执分别复用控制面的既有 Owner。 */
    public DmnDraftController(ControlApplicationService service, ControlCommandExecutor commands, ObjectMapper mapper) {
        this.service = service; this.commands = commands; this.mapper = mapper;
    }

    /** 先校验当前权限和范围，再读取正文及查询幂等回执。 */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public DmnDraftView save(@PathVariable String campaignId, @RequestHeader("Idempotency-Key") String key,
            HttpServletRequest request) {
        service.authorizeDmnWrite(campaignId);
        JsonNode node = read(request);
        DmnDraftTable.object(node, Set.of("expectedVersion", "table"));
        JsonNode version = node.get("expectedVersion");
        if (!version.isIntegralNumber() || !version.canConvertToLong() || version.asLong() < 0
                || version.asLong() == Long.MAX_VALUE) throw DmnDraftError.VERSION_INVALID.invalid();
        var payload = new SaveCommand(campaignId, version.asLong(), DmnDraftTable.read(node.get("table")));
        return commands.executeDraft(TenantContextHolder.requireCurrent().tenantId(), SAVE_OPERATION, key,
                payload, DmnDraftView.class,
                () -> service.saveDmnDraft(campaignId, payload.expectedVersion(), payload.table(), key));
    }

    /** 未保存返回明确404，由编辑器决定是否建立空白草稿。 */
    @GetMapping("/latest")
    public DmnDraftView latest(@PathVariable String campaignId) { return service.latestDmnDraft(campaignId); }

    /** 历史版本与最新版本使用相同的当前活动访问约束。 */
    @GetMapping("/versions/{version}")
    public DmnDraftView version(@PathVariable String campaignId, @PathVariable long version) {
        return service.dmnDraftVersion(campaignId, version);
    }

    private JsonNode read(HttpServletRequest request) {
        if (request.getContentLengthLong() > MAX_REQUEST_BYTES) throw DmnDraftError.REQUEST_TOO_LARGE.invalid();
        try {
            byte[] body = request.getInputStream().readNBytes(MAX_REQUEST_BYTES + 1);
            if (body.length > MAX_REQUEST_BYTES) throw DmnDraftError.REQUEST_TOO_LARGE.invalid();
            return mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(body);
        } catch (IOException | JacksonException invalid) {
            throw DmnDraftError.INPUT_INVALID.invalid();
        }
    }
    private record SaveCommand(String campaignId, long expectedVersion, DmnDraftTable table) { }
}
