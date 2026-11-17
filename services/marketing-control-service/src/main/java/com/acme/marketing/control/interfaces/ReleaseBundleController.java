package com.acme.marketing.control.interfaces;

import com.acme.marketing.contracts.release.ReleaseBundleManifest;
import com.acme.marketing.control.application.ControlCommandExecutor;
import com.acme.marketing.control.application.ReleaseBundleError;
import com.acme.marketing.control.application.ReleaseBundleService;
import com.acme.marketing.control.application.ReleaseBundleService.BundleView;
import com.acme.marketing.control.application.ReleaseBundleService.PrepareRequest;
import com.acme.marketing.platform.web.TenantContextHolder;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.ArrayList;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 活动组合准备入口；严格有界解析，避免身份已失效时读取旧写入回执。 */
@RestController
@RequestMapping("/api/v1/campaigns/{campaignId}/release-bundles")
public final class ReleaseBundleController {
    private static final int MAX_REQUEST_BYTES = 16 * 1024;
    private final ReleaseBundleService service;
    private final ControlCommandExecutor commands;
    private final ObjectMapper mapper;

    /** 幂等仍由控制面统一命令事务实现，不新增第二套回执存储。 */
    public ReleaseBundleController(ReleaseBundleService service, ControlCommandExecutor commands, ObjectMapper mapper) {
        this.service = service; this.commands = commands; this.mapper = mapper;
    }

    /** 当前授权先于解析和重放；活动与作者都进入规范命令摘要。 */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public BundleView prepare(@PathVariable String campaignId, @RequestHeader("Idempotency-Key") String key,
            HttpServletRequest request) {
        service.authorizeWrite(campaignId);
        var payload = new PrepareCommand(campaignId, TenantContextHolder.requireCurrent().actorId(), read(request));
        return commands.executeDraft(TenantContextHolder.requireCurrent().tenantId(), ReleaseBundleService.SAVE_OPERATION,
                key, payload, BundleView.class, () -> service.prepare(campaignId, payload.request(), key));
    }

    /** 未准备时返回404，不创建假组合。 */
    @GetMapping("/latest")
    public BundleView latest(@PathVariable String campaignId) { return service.latest(campaignId); }

    /** 历史版本使用相同的当前权限与数据范围。 */
    @GetMapping("/versions/{version}")
    public BundleView version(@PathVariable String campaignId, @PathVariable long version) {
        return service.version(campaignId, version);
    }

    /** 导出完整组合来源证明；其内容不能直接交给旧激活接口。 */
    @GetMapping("/versions/{version}/manifest")
    public ReleaseBundleManifest manifest(@PathVariable String campaignId, @PathVariable long version) {
        return service.manifest(campaignId, version);
    }

    private PrepareRequest read(HttpServletRequest request) {
        if (request.getContentLengthLong() > MAX_REQUEST_BYTES) throw ReleaseBundleError.REQUEST_TOO_LARGE.invalid();
        try {
            byte[] bytes = request.getInputStream().readNBytes(MAX_REQUEST_BYTES + 1);
            if (bytes.length > MAX_REQUEST_BYTES) throw ReleaseBundleError.REQUEST_TOO_LARGE.invalid();
            JsonNode body = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(bytes);
            if (body == null || !body.isObject() || body.size() != 2 || !body.has("expectedVersion")
                    || !body.has("manifestIds")) throw ReleaseBundleError.INPUT_INVALID.invalid();
            JsonNode version = body.get("expectedVersion"), ids = body.get("manifestIds");
            if (!version.isIntegralNumber() || !version.canConvertToLong() || !ids.isArray())
                throw ReleaseBundleError.INPUT_INVALID.invalid();
            var members = new ArrayList<String>();
            for (JsonNode id : ids) {
                if (!id.isString()) throw ReleaseBundleError.INPUT_INVALID.invalid();
                members.add(id.asString());
            }
            return new PrepareRequest(version.asLong(), members);
        } catch (IOException | JacksonException malformed) {
            throw ReleaseBundleError.INPUT_INVALID.invalid();
        }
    }

    private record PrepareCommand(String campaignId, String actorId, PrepareRequest request) { }
}
