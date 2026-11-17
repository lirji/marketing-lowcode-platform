package com.acme.marketing.gateway;

import com.acme.marketing.platform.web.TenantContextHolder;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

/** 身份链和独立 Redis 配额之后接收有界诊断；不触发业务事务或外部调用。 */
@RestController
public final class ClientErrorController {
    static final String PATH = "/api/v1/client-errors";
    private static final int MAX_REQUEST_BYTES = 1024;
    private static final Logger LOG = LoggerFactory.getLogger(ClientErrorController.class);
    private final ObjectMapper mapper;
    private final MeterRegistry meters;

    /** 复用项目 JSON 与指标设施，不引入第二套采集框架。 */
    public ClientErrorController(ObjectMapper mapper, MeterRegistry meters) {
        this.mapper = mapper; this.meters = meters;
    }

    /** 204 仅代表当前实例完成日志调用，不承诺持久留存、去重或告警已送达。 */
    @PostMapping(path = PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> collect(HttpServletRequest request) {
        var scope = TenantContextHolder.requireCurrent();
        if (request.getContentLengthLong() > MAX_REQUEST_BYTES) throw ClientErrorCode.REQUEST_TOO_LARGE.invalid();
        ClientErrorEvent event;
        try {
            byte[] body = request.getInputStream().readNBytes(MAX_REQUEST_BYTES + 1);
            if (body.length > MAX_REQUEST_BYTES) throw ClientErrorCode.REQUEST_TOO_LARGE.invalid();
            String utf8 = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString();
            var json = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readTree(utf8);
            event = ClientErrorEvent.read(json);
        } catch (IOException | JacksonException invalid) { throw ClientErrorCode.INPUT_INVALID.invalid(); }
        // 租户摘要只用于日志关联，不能称为匿名化；不作为指标标签，也不记录操作者。
        LOG.warn("client_error schemaVersion=1 source={} route={} httpStatus={} backendTraceId={} tenantHash={}",
                event.source().code(), event.route().code(), event.httpStatus(),
                event.backendTraceId() == null ? "none" : event.backendTraceId(), tenantHash(scope.tenantId().value()));
        meters.counter("marketing.console.errors", "source", event.source().code(), "route", event.route().code(),
                "status_class", event.statusClass()).increment();
        return ResponseEntity.noContent().header("Cache-Control", "no-store").build();
    }

    private static String tenantHash(String tenant) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(tenant.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is required by the JVM", impossible); }
    }
}
