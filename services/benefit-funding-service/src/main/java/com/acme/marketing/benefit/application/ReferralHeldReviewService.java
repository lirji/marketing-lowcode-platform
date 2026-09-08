package com.acme.marketing.benefit.application;

import com.acme.marketing.benefit.application.ReferralIntakeIdentityPort.Binding;
import com.acme.marketing.benefit.application.ReferralIntakeConfirmationPort.*;
import com.acme.marketing.benefit.application.ReferralHeldReviewRepository.*;
import com.acme.marketing.benefit.domain.ReferralAwardPreparation.Phase;
import com.acme.marketing.platform.identity.TenantScope;
import com.acme.marketing.platform.error.ConflictException;
import java.time.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** 持续调用的取消同步与发送前观察，始终保留HELD；没有网络发送或可投递许可输出。 */
public final class ReferralHeldReviewService {
    private final ReferralPreparationRepository preparations;private final ReferralIntakeRepository intake;private final ReferralHeldReviewRepository reviews;
    private final ReferralIntakeIdentityPort identities;private final ReferralIntakeConfirmationPort confirmations;private final ReferralIntakeRiskPort risk;
    private final ReferralCandidateSnapshotService protection;private final AwardDispatchModeRouter modes;private final Clock clock;private final Duration maximum;
    private final boolean enabled;private final TransactionTemplate tx;
    /** 默认关闭；证明最大寿命必须由已确认配置给出，不采用生产推测值。 */
    public ReferralHeldReviewService(ReferralPreparationRepository preparations,ReferralIntakeRepository intake,ReferralHeldReviewRepository reviews,
            ReferralIntakeIdentityPort identities,ReferralIntakeConfirmationPort confirmations,ReferralIntakeRiskPort risk,
            ReferralCandidateSnapshotService protection,AwardDispatchModeRouter modes,Clock clock,PlatformTransactionManager manager,boolean enabled,Duration maximum) {
        this.preparations=preparations;this.intake=intake;this.reviews=reviews;this.identities=identities;this.confirmations=confirmations;this.risk=risk;
        this.protection=protection;this.modes=modes;this.clock=clock;this.enabled=enabled;this.maximum=maximum;
        if(enabled)require(maximum!=null && !maximum.isZero() && !maximum.isNegative());
        tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    /** 取消/结果回流不因租户切回旧投递模式停止；未来调度器可重复调用，不创建新的确认身份。 */
    public View syncCancellation(TenantScope scope,String request){return inspect(scope,request,false);}
    /** 仅记录当前在线复核观察；调用者不能据此直接发送，也不改变旧HELD状态。 */
    public View reviewBeforeDispatch(TenantScope scope,String request){return inspect(scope,request,true);}
    private View inspect(TenantScope scope,String request,boolean checkRisk) {
        require(!TransactionSynchronizationManager.isActualTransactionActive());if(!enabled)return new View(true,null);
        scope.requirePermission("referral:award");if(checkRisk)require(modes.modeFor(scope.tenantId().value())==AwardDispatchModeRouter.DeliveryMode.CENTER);
        Binding binding;try{binding=identities.resolve(scope,request);}catch(RuntimeException unavailable){throw rejected();}
        require(binding!=null && binding.identity().tenantId().equals(scope.tenantId().value()) && binding.identity().sourceRequestId().equals(request));
        scope.requireOrganization(binding.organizationId());scope.requireShop(binding.shopId());
        var stored=preparations.find(binding.identity().tenantId(),request).orElseThrow(ReferralHeldReviewService::rejected);
        var existing=intake.find(binding.identity().tenantId(),request);
        require(stored.state().identity().equals(binding.identity()) && existing!=null && existing.binding().equals(binding));
        Result remote;try{remote=confirmations.recover(binding.identity());}catch(RuntimeException unavailable){remote=null;}
        if(remote!=null && remote.currentState()!=State.UNKNOWN)ReferralIntakeProofs.confirmation(binding,remote,clock.instant(),maximum);
        ReferralIntakeRiskPort.Decision decision=null;
        // 先观察取消，取消路径不能被风险系统不可用遮住；同步模式不读取主体密文。
        if(checkRisk && remote!=null && remote.currentState()==State.CONFIRMED) {
            var payload=protection.restore(binding.identity(),stored.snapshot());
            try{decision=risk.evaluate(binding,payload);}catch(RuntimeException unavailable){decision=null;}
            if(decision!=null)validateRisk(binding,decision,clock.instant());
        }
        Result response=remote;var result=decision;
        return tx.execute(s->{
            var current=preparations.lock(binding.identity());var context=intake.lock(binding);
            boolean known=response!=null && response.currentState()!=State.UNKNOWN;
            if(known){ReferralIntakeProofs.confirmation(binding,response,clock.instant(),maximum);context=intake.observe(binding,response);}
            Status status;Instant until=null;
            if(context.quarantined() || current.state().quarantined())status=Status.QUARANTINED;
            else if(context.cancelled())status=Status.CANCELLED;
            else if(!known || context.confirmation()==null || response.currentRevision()!=context.confirmation().currentRevision())status=Status.UNKNOWN;
            else if(context.confirmation().currentState()!=State.CONFIRMED || current.state().phase()!=Phase.ACCEPTED || context.acceptedIntentId()==null)status=Status.BLOCKED;
            else if(current.state().receipt()==null || !current.state().acceptedIntentId().equals(context.acceptedIntentId())
                    || !current.state().receipt().confirmationId().equals(context.confirmation().confirmationId())
                    || !current.state().receipt().confirmedAt().equals(context.confirmation().confirmedAt()))status=Status.BLOCKED;
            else if(!checkRisk)status=Status.UNKNOWN;
            else {
                if(result!=null)validateRisk(binding,result,clock.instant());
                intake.recordRisk(binding,result);context=intake.lock(binding);
                if(result!=null && result.action()==ReferralIntakeRiskPort.Action.ALLOW && "ALLOW".equals(context.riskAction())) {
                    require(modes.modeFor(binding.identity().tenantId())==AwardDispatchModeRouter.DeliveryMode.CENTER);
                    status=Status.CHECKED;until=result.expiresAt().isBefore(response.expiresAt())?result.expiresAt():response.expiresAt();
                } else status=Status.BLOCKED;
            }
            long revision=context.confirmation()==null?0:context.confirmation().currentRevision();long cancel=context.confirmation()==null?0:context.confirmation().cancelRevision();
            var observation=reviews.record(binding,status,revision,cancel,clock.instant(),until);
            // 已验明的取消必须提交；只有有利的CHECKED结论需要在最后写入后再次确认所有当前期限。
            if(status==Status.CHECKED){require(modes.modeFor(binding.identity().tenantId())==AwardDispatchModeRouter.DeliveryMode.CENTER);validateRisk(binding,result,clock.instant());ReferralIntakeProofs.confirmation(binding,response,clock.instant(),maximum);}
            return new View(false,observation);
        });
    }
    private void validateRisk(Binding binding,ReferralIntakeRiskPort.Decision decision,Instant now) {
        require(binding.identity().equals(decision.identity()) && decision.action()!=null);
        if(decision.action()==ReferralIntakeRiskPort.Action.ALLOW || decision.action()==ReferralIntakeRiskPort.Action.REJECT)require(decision.decisionId()!=null && !decision.decisionId().isBlank());
        ReferralIntakeProofs.window(decision.issuedAt(),decision.expiresAt(),now,maximum);
    }
    private static void require(boolean condition){if(!condition)throw rejected();}
    private static ConflictException rejected(){return new ConflictException("REFERRAL_HELD_REVIEW_UNAVAILABLE","referral held review unavailable or conflicts");}
    /** 返回值没有payload、投递token或成功履约状态，必须保持业务含义为观察。 */
    public record View(boolean disabled,Observation observation){}
}
