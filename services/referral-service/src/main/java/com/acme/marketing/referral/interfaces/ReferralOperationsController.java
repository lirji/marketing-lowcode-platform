package com.acme.marketing.referral.interfaces;

import com.acme.marketing.platform.web.TenantContextHolder;
import com.acme.marketing.referral.application.query.ReferralOperationsService;
import com.acme.marketing.referral.application.query.ReferralOperationsService.*;
import org.springframework.web.bind.annotation.*;

/** 管理端只读真实资源，机器/用户身份由既有安全过滤链验证；不开放内部发奖或任意改状态。 */
@RestController
@RequestMapping("/api/v1/referral-campaigns/{campaignId}")
public class ReferralOperationsController {
    private final ReferralOperationsService service;
    /** 仅注入应用查询服务，不直接访问Mapper。 */
    public ReferralOperationsController(ReferralOperationsService service){this.service=service;}
    /** 独立参与者资源，进度尚未计算时保留null。 */
    @GetMapping("/participants")
    public Page<Participant> participants(@PathVariable String campaignId,@RequestParam(required=false) String after,@RequestParam(defaultValue="20") int limit){return service.participants(TenantContextHolder.requireCurrent(),campaignId,after,limit);}
    /** 独立关系和资格资源，避免将BOUND当成资格通过。 */
    @GetMapping("/relations")
    public Page<Relation> relations(@PathVariable String campaignId,@RequestParam(required=false) String after,@RequestParam(defaultValue="20") int limit){return service.relations(TenantContextHolder.requireCurrent(),campaignId,after,limit);}
    /** 独立奖励资源，前端按五维状态显示到账/待处理/追回。 */
    @GetMapping("/rewards")
    public Page<Reward> rewards(@PathVariable String campaignId,@RequestParam(required=false) String after,@RequestParam(defaultValue="20") int limit){return service.rewards(TenantContextHolder.requireCurrent(),campaignId,after,limit);}
    /** 当前数据库投影统计，明确区分资格人数、奖励份数与追回状态。 */
    @GetMapping("/summary")
    public Summary summary(@PathVariable String campaignId){return service.summary(TenantContextHolder.requireCurrent(),campaignId);}
}
