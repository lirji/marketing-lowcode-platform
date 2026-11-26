package com.acme.marketing.eventgateway.interfaces;

import com.acme.marketing.eventgateway.application.EventIngestionService;
import com.acme.marketing.platform.web.PersistentIdempotentCommandExecutor;
import com.acme.marketing.platform.web.TenantContextHolder;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class EventGatewayController {
    private final EventIngestionService service;
    private final PersistentIdempotentCommandExecutor commands;

    public EventGatewayController(EventIngestionService service, PersistentIdempotentCommandExecutor commands) {
        this.service = service;
        this.commands = commands;
    }

    @PostMapping("/events/sources")
    @ResponseStatus(HttpStatus.CREATED)
    public EventIngestionService.SourceView register(@RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody EventIngestionService.RegisterSourceRequest request) {
        return commands.execute(TenantContextHolder.requireCurrent().tenantId(), "event.source.create",
                key, request, EventIngestionService.SourceView.class, () -> service.registerSource(request));
    }

    @PostMapping("/events")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public EventIngestionService.Receipt ingest(
            @Valid @RequestBody EventIngestionService.InboundEvent event) {
        return service.ingest(event);
    }

    /** 查询原租户已持久受理的回执。 */
    @GetMapping("/events/receipts/{number}")
    public EventIngestionService.Receipt receipt(@PathVariable String number) {
        return service.receipt(number);
    }

    /** 隔离详情支持回执号和隔离号两条追溯入口。 */
    @GetMapping("/quarantine")
    public List<EventIngestionService.QuarantineView> quarantine(@RequestParam(required = false) String number) {
        return service.quarantine(number);
    }

    @PostMapping("/quarantine/{quarantineId}:replay")
    public EventIngestionService.Receipt replay(@PathVariable String quarantineId) {
        return service.replay(quarantineId);
    }
}
