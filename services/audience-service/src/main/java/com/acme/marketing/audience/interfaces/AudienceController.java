package com.acme.marketing.audience.interfaces;

import com.acme.marketing.audience.application.AudienceService;
import com.acme.marketing.platform.web.PersistentIdempotentCommandExecutor;
import com.acme.marketing.platform.web.TenantContextHolder;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class AudienceController {
    private final AudienceService service;
    private final PersistentIdempotentCommandExecutor commands;

    public AudienceController(AudienceService service, PersistentIdempotentCommandExecutor commands) {
        this.service = service;
        this.commands = commands;
    }

    @PostMapping("/fields") @ResponseStatus(HttpStatus.CREATED)
    public AudienceService.FieldDefinition register(@RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody AudienceService.FieldDefinition request) {
        return commands.execute(TenantContextHolder.requireCurrent().tenantId(), "audience.field.create",
                key, request, AudienceService.FieldDefinition.class, () -> service.registerField(request));
    }

    @GetMapping("/fields")
    public List<AudienceService.FieldDefinition> fields() {
        return service.fields();
    }

    @PostMapping("/audiences") @ResponseStatus(HttpStatus.CREATED)
    public AudienceService.SegmentView create(@RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody AudienceService.CreateSegmentRequest request) {
        return commands.execute(TenantContextHolder.requireCurrent().tenantId(), "audience.segment.create",
                key, request, AudienceService.SegmentView.class, () -> service.createSegment(request));
    }

    @GetMapping("/audiences")
    public List<AudienceService.SegmentView> audiences() {
        return service.audiences();
    }

    @PostMapping("/audiences/{segmentId}/versions/{version}:preview")
    public AudienceService.Preview preview(@PathVariable String segmentId, @PathVariable long version,
            @RequestBody List<AudienceService.SubjectProfile> profiles) {
        return service.preview(segmentId, version, profiles);
    }

    @PostMapping("/audiences/{segmentId}/versions/{version}/snapshots") @ResponseStatus(HttpStatus.CREATED)
    public AudienceService.SnapshotView snapshot(@PathVariable String segmentId, @PathVariable long version,
            @RequestBody AudienceService.SnapshotRequest request) {
        return service.createSnapshot(segmentId, version, request);
    }

    @PutMapping("/audiences/snapshots/{snapshotId}/memberships")
    public AudienceService.MembershipView update(@PathVariable String snapshotId,
            @RequestBody AudienceService.MembershipUpdate request) {
        return service.updateMembership(snapshotId, request);
    }

    @GetMapping("/audiences/snapshots/{snapshotId}/memberships/{subjectToken}")
    public AudienceService.MembershipView membership(@PathVariable String snapshotId,
            @PathVariable String subjectToken, @RequestParam Instant usedAt,
            @RequestParam AudienceService.StalePolicy stalePolicy) {
        return service.membership(snapshotId, subjectToken, usedAt, stalePolicy);
    }
}
