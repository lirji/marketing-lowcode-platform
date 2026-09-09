package com.acme.marketing.referral.interfaces;

import com.acme.marketing.platform.web.TenantContextHolder;
import com.acme.marketing.referral.application.reevaluation.ReferralReevaluationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** 受控复评只返回持久排队收据，不接受受益人、券、金额或资格覆盖值。 */
@RestController
public class ReferralReevaluationController {
    private final ReferralReevaluationService service;
    /** 鉴权后转应用服务，控制器不直接访问数据库或发券渠道。 */
    public ReferralReevaluationController(ReferralReevaluationService service){this.service=service;}
    /** 202仅表示复评已排队；重试需复用原Idempotency-Key。 */
    @PostMapping("/api/v1/referral-rewards/{rewardId}:reevaluate")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ReferralReevaluationService.Receipt reevaluate(@PathVariable String rewardId,
            @RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Request request){
        return service.submit(TenantContextHolder.requireCurrent(),rewardId,key,request.reason());
    }
    /** 原因必填且有界，审计保存但公开事件不传播原因文本。 */
    public record Request(@NotBlank @Size(max=128) String reason) {}
}
