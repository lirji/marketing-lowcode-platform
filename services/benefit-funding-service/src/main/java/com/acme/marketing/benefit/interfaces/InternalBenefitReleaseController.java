package com.acme.marketing.benefit.interfaces;

import com.acme.marketing.benefit.application.BenefitFundingService;
import java.util.Set;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 供营销控制面在 stage 前同步校验审核制品所引用的权益闭包。 */
@RestController
@RequestMapping("/internal/v1")
public class InternalBenefitReleaseController {
    private final BenefitFundingService service;

    public InternalBenefitReleaseController(BenefitFundingService service) {
        this.service = service;
    }

    @PostMapping("/benefits:assert-releasable")
    public BenefitFundingService.ReleaseEligibility assertReleasable(
            @RequestBody ReleaseEligibilityRequest request) {
        return service.assertReleasable(request.references());
    }

    public record ReleaseEligibilityRequest(Set<String> references) { }
}
