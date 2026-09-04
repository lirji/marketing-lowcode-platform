package com.acme.marketing.measurement.interfaces;

import com.acme.marketing.contracts.event.MarketingFact;
import com.acme.marketing.measurement.application.MeasurementService;
import com.acme.marketing.platform.web.TenantContextHolder;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class MeasurementController {
    private final MeasurementService service;
    public MeasurementController(MeasurementService service) { this.service = service; }

    @PostMapping("/experiments") @ResponseStatus(HttpStatus.CREATED)
    public MeasurementService.ExperimentView create(@RequestBody MeasurementService.ExperimentRequest request) {
        return service.createExperiment(request);
    }

    @PostMapping("/experiments/{experimentId}/versions/{version}:assign")
    public MeasurementService.AssignmentView assign(@PathVariable String experimentId, @PathVariable String version,
            @RequestBody AssignmentRequest request) { return service.assign(experimentId, version, request.unit()); }

    @PostMapping("/experiments/{experimentId}/versions/{version}:check-srm")
    public MeasurementService.SrmReport srm(@PathVariable String experimentId, @PathVariable String version) {
        return service.checkSrm(experimentId, version);
    }

    @PostMapping("/measurements/facts") @ResponseStatus(HttpStatus.ACCEPTED)
    public MeasurementService.FactReceipt fact(@RequestBody FactRequest request) {
        var tenantId = TenantContextHolder.requireCurrent().tenantId();
        return service.ingest(new MarketingFact(request.eventId(), tenantId, request.type(), request.businessKey(),
                request.subjectToken(), request.occurredAt(), request.ingestedAt(), request.schemaVersion(),
                request.attributes(), request.correctionOf()));
    }

    @PostMapping("/measurements/watermarks")
    public MeasurementService.ProjectionWatermark watermark(
            @RequestBody MeasurementService.WatermarkRequest request) {
        return service.advanceWatermark(request);
    }

    @GetMapping("/measurements/dashboard")
    public MeasurementService.Dashboard dashboard(@RequestParam Instant from, @RequestParam Instant to) {
        return service.dashboard(from, to);
    }

    @GetMapping("/measurements/series")
    public MeasurementService.Series series(@RequestParam Instant from, @RequestParam Instant to,
            @RequestParam String granularity) {
        return service.series(from, to, granularity);
    }

    @PostMapping("/measurements/attribution:recompute")
    public MeasurementService.AttributionRecomputeResult recomputeAttribution(
            @RequestHeader("Idempotency-Key") String commandId) {
        return service.recomputeAttribution(commandId);
    }

    @GetMapping("/measurements/attribution/{conversionEventId}")
    public MeasurementService.AttributionResult attribution(@PathVariable String conversionEventId,
            @RequestParam MeasurementService.AttributionPolicy policy, @RequestParam long windowSeconds) {
        return service.attribute(conversionEventId, policy, windowSeconds);
    }

    @PostMapping("/traces") @ResponseStatus(HttpStatus.CREATED)
    public MeasurementService.TraceView trace(@RequestBody MeasurementService.TraceRequest request) {
        return service.recordTrace(request);
    }

    @GetMapping("/traces/requests/{requestId}")
    public MeasurementService.TraceView traceByRequest(@PathVariable String requestId) {
        return service.traceByRequest(requestId);
    }

    @GetMapping("/traces/orders/{orderId}")
    public MeasurementService.TraceView traceByOrder(@PathVariable String orderId) {
        return service.traceByOrder(orderId);
    }

    public record AssignmentRequest(String unit) { }
    public record FactRequest(String eventId, MarketingFact.Type type, String businessKey, String subjectToken,
            Instant occurredAt, Instant ingestedAt, String schemaVersion, Map<String, String> attributes,
            String correctionOf) { }
}
