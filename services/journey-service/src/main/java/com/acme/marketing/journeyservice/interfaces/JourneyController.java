package com.acme.marketing.journeyservice.interfaces;

import com.acme.marketing.journey.JourneyPlan;
import com.acme.marketing.journey.JourneySignal;
import com.acme.marketing.journeyservice.application.JourneyApplicationService;
import com.acme.marketing.journeyservice.application.JourneyRuntimeReleaseService;
import com.acme.marketing.contracts.release.ActivationDirective;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class JourneyController {
    private final JourneyApplicationService service;
    private final JourneyRuntimeReleaseService runtimeReleases;
    public JourneyController(JourneyApplicationService service, JourneyRuntimeReleaseService runtimeReleases) {
        this.service = service;
        this.runtimeReleases = runtimeReleases;
    }

    @PutMapping("/journey-runtime/manifest")
    public JourneyRuntimeReleaseService.WarmView warm(
            @RequestBody JourneyRuntimeReleaseService.WarmRequest request) {
        return runtimeReleases.warm(request);
    }

    @PutMapping("/journey-runtime/activation")
    public JourneyRuntimeReleaseService.ActiveView activate(@RequestBody ActivationDirective directive) {
        return runtimeReleases.activate(directive);
    }

    @PutMapping("/journeys/{journeyId}/versions/{version}")
    public JourneyPlan register(@PathVariable String journeyId, @PathVariable long version,
            @RequestBody JourneyPlan plan) {
        if (!journeyId.equals(plan.journeyId()) || version != plan.version()) {
            throw new IllegalArgumentException("path and journey plan version mismatch");
        }
        return service.register(plan);
    }

    @PostMapping("/enrollments") @ResponseStatus(HttpStatus.CREATED)
    public JourneyApplicationService.EnrollmentView enroll(
            @RequestBody JourneyApplicationService.EnrollRequest request) { return service.enroll(request); }

    @PostMapping("/enrollments/{enrollmentId}/signals")
    public JourneyApplicationService.EnrollmentView signal(@PathVariable String enrollmentId,
            @RequestBody SignalRequest request) {
        JourneySignal signal = switch (request.type()) {
            case EVENT -> new JourneySignal.Event(request.signalId(), request.name(), request.occurredAt(), request.attributes());
            case TIMER -> new JourneySignal.Timer(request.signalId(), request.name(), request.occurredAt());
        };
        return service.signal(enrollmentId, signal);
    }

    @GetMapping("/enrollments/{enrollmentId}")
    public JourneyApplicationService.EnrollmentView get(@PathVariable String enrollmentId) {
        return service.get(enrollmentId);
    }

    @GetMapping("/enrollments")
    public List<JourneyApplicationService.EnrollmentView> enrollments(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String journeyId,
            @RequestParam(defaultValue = "50") int limit) {
        return service.enrollments(status, journeyId, limit);
    }

    @PostMapping("/journeys/{journeyId}:migrate")
    public JourneyApplicationService.MigrationReport migrate(@PathVariable String journeyId,
            @RequestBody JourneyApplicationService.MigrationRequest request) {
        return service.migrate(journeyId, request);
    }

    public enum SignalType { EVENT, TIMER }
    public record SignalRequest(SignalType type, String signalId, String name, Instant occurredAt,
            Map<String, String> attributes) {
        public SignalRequest { attributes = Map.copyOf(attributes == null ? Map.of() : attributes); }
    }
}
