package com.acme.marketing.benefit.application;

import com.acme.marketing.benefit.application.ReferralIntakeIdentityPort.Binding;
import com.acme.marketing.benefit.application.ReferralIntakeConfirmationPort.*;
import com.acme.marketing.benefit.application.ReferralIntakeRiskPort.Decision;
import com.acme.marketing.benefit.application.ReferralPreparationRepository.*;
import com.acme.marketing.benefit.domain.ReferralAwardPreparation;
import com.acme.marketing.benefit.domain.ReferralAwardPreparation.*;
import com.acme.marketing.platform.error.ConflictException;
import com.acme.marketing.platform.identity.TenantScope;
import java.time.*;
import java.util.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;

/** 专用裂变HELD受理；准备先提交、远端事务外、receipt/意图/expected同事务，不触旧Drools或自动发送。 */
public final class ReferralAwardIntakeService {
    private final ReferralAwardIntentAssembler assembler;private final ReferralPreparationRepository preparations;
    private final ReferralIntakeRepository intake;private final ReferralCandidateSnapshotService protection;
    private final ReferralIntakeIdentityPort identities;private final ReferralIntakeRiskPort risk;private final ReferralIntakeConfirmationPort confirmations;
    private final AwardDispatchModeRouter modes;private final Clock clock;private final Duration lease,proofLifetime;
    private final boolean enabled;private final TransactionTemplate tx;
    /** 生产lease/证明水位无默认值，开关关闭时不触任何可信端口或数据库。 */
    public ReferralAwardIntakeService(ReferralAwardIntentAssembler assembler,ReferralPreparationRepository preparations,ReferralIntakeRepository intake,
            ReferralCandidateSnapshotService protection,ReferralIntakeIdentityPort identities,ReferralIntakeRiskPort risk,
            ReferralIntakeConfirmationPort confirmations,AwardDispatchModeRouter modes,Clock clock,PlatformTransactionManager manager,
            boolean enabled,Duration lease,Duration proofLifetime) {
        this.assembler=assembler;this.preparations=preparations;this.intake=intake;this.protection=protection;this.identities=identities;this.risk=risk;this.confirmations=confirmations;
        this.modes=modes;this.clock=clock;this.enabled=enabled;this.lease=lease;this.proofLifetime=proofLifetime;
        if(enabled && (lease==null || lease.isZero() || lease.isNegative() || proofLifetime==null || proofLifetime.isZero() || proofLifetime.isNegative()))throw rejected();
        tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    /** scope来自已认证机器；token只用于首次/尚未开始确认的当前签名，不用于永久回放身份猜测。 */
    public View intake(TenantScope scope,String sourceRequestId,String token) {
        if(TransactionSynchronizationManager.isActualTransactionActive())throw rejected();
        if(!enabled)return new View(Status.DISABLED,null,false);
        scope.requirePermission("referral:award");require(modes.modeFor(scope.tenantId().value())==AwardDispatchModeRouter.DeliveryMode.CENTER);
        Binding binding;
        try {binding=identities.resolve(scope,sourceRequestId);}catch(RuntimeException unavailable){throw rejected();}
        require(binding!=null && binding.identity().tenantId().equals(scope.tenantId().value()) && binding.identity().sourceRequestId().equals(sourceRequestId));
        scope.requireOrganization(binding.organizationId());scope.requireShop(binding.shopId());
        Stored stored=preparations.find(binding.identity().tenantId(),sourceRequestId).orElse(null);
        var context=intake.find(binding.identity().tenantId(),sourceRequestId);
        if(stored!=null){require(stored.state().identity().equals(binding.identity()) && context!=null && context.binding().equals(binding));var done=terminal(stored.state(),context,true);if(done!=null)return done;}
        String ownerId="intake:"+UUID.randomUUID();
        Instant authIssued=null,authExpires=null;
        if(stored==null || stored.state().phase()==Phase.PREPARED) {
            var validated=assembler.assembleValidatedCandidate(scope,binding.organizationId(),binding.shopId(),sourceRequestId,token);
            require(binding.matches(validated.claims()) && binding.identity().payloadHash().equals(validated.candidate().payloadHash()));
            authIssued=validated.claims().issuedAt();authExpires=validated.claims().expiresAt();
            if(stored==null) {
                var snapshot=protection.protect(binding.identity(),validated.candidate());Instant issued=authIssued,expires=authExpires;
                stored=tx.execute(s->{var prepared=preparations.prepare(binding.identity(),snapshot,ownerId,clock.instant().plus(lease));intake.reserve(binding,issued,expires);return prepared;});
            }
        }
        require(stored!=null);Instant acquiredAuthIssued=authIssued,acquiredAuthExpires=authExpires;
        Stored acquired=tx.execute(s->{
            var current=preparations.lock(binding.identity());var c=intake.lock(binding);
            if(terminal(current.state(),c,true)!=null)return current;
            if(current.state().lease().owner().equals(ownerId))return current;
            if(clock.instant().isBefore(current.state().lease().expiresAt()))return null;
            return preparations.apply(binding.identity(),new TakeOver(ownerId,clock.instant().plus(lease)));
        });
        if(acquired==null)return new View(Status.IN_PROGRESS,null,false);
        context=intake.find(binding.identity().tenantId(),sourceRequestId);var done=terminal(acquired.state(),context,true);if(done!=null)return done;
        var owner=new Owner(ownerId,acquired.state().lease().fence());
        var payload=protection.restore(binding.identity(),acquired.snapshot());
        Decision decision;
        try {decision=risk.evaluate(binding,payload);}catch(RuntimeException unavailable){decision=null;}
        if(decision!=null){require(binding.identity().equals(decision.identity()) && decision.action()!=null);window(decision.issuedAt(),decision.expiresAt(),clock.instant());}
        Decision observedRisk=decision;
        if(decision!=null && (decision.action()==ReferralIntakeRiskPort.Action.ALLOW || decision.action()==ReferralIntakeRiskPort.Action.REJECT))require(decision.decisionId()!=null && !decision.decisionId().isBlank());
        var admission=decision==null || decision.action()!=ReferralIntakeRiskPort.Action.ALLOW?null:new Admission(binding.identity(),true,decision.decisionId(),decision.issuedAt(),decision.expiresAt(),
                acquiredAuthIssued==null?context.authorizationIssuedAt():acquiredAuthIssued,acquiredAuthExpires==null?context.authorizationExpiresAt():acquiredAuthExpires);
        View riskResult=tx.execute(s->{
            var current=preparations.lock(binding.identity());var c=intake.lock(binding);var stopped=terminal(current.state(),c,true);if(stopped!=null)return stopped;
            owned(current.state(),owner,clock.instant());
            // 拒绝同样会永久生效，不能把锁等待期间已经过期的风险回执冻结为永久拒绝。
            if(observedRisk!=null)window(observedRisk.issuedAt(),observedRisk.expiresAt(),clock.instant());
            intake.recordRisk(binding,observedRisk);
            if(observedRisk==null || observedRisk.action()!=ReferralIntakeRiskPort.Action.ALLOW)return new View(observedRisk!=null && observedRisk.action()==ReferralIntakeRiskPort.Action.REJECT?Status.RISK_REJECTED:Status.PENDING_RISK,null,false);
            window(observedRisk.issuedAt(),observedRisk.expiresAt(),clock.instant());
            if(current.state().phase()==Phase.PREPARED)preparations.apply(binding.identity(),new Begin(owner,admission));
            return null;
        });
        if(riskResult!=null)return riskResult;
        Result response;
        try {response=acquired.state().phase()==Phase.PREPARED?confirmations.confirm(binding.identity()):confirmations.recover(binding.identity());}
        catch(RuntimeException unavailable){response=null;}
        if(response==null || response.currentState()==State.UNKNOWN) {
            tx.executeWithoutResult(s->{var current=preparations.lock(binding.identity());
                if(current.state().lease().owner().equals(owner.owner()) && current.state().lease().fence()==owner.fence()
                        && clock.instant().isBefore(current.state().lease().expiresAt()) && (current.state().phase()==Phase.CONFIRMING || current.state().phase()==Phase.CONFIRM_UNKNOWN))
                    preparations.apply(binding.identity(),new Unknown(owner));});
            return new View(Status.CONFIRMATION_UNKNOWN,null,false);
        }
        validate(binding,response,clock.instant());Result confirmed=response;
        return tx.execute(s->finish(binding,owner,admission,confirmed));
    }

    private View finish(Binding binding,Owner owner,Admission admission,Result response) {
        var stored=preparations.lock(binding.identity());intake.lock(binding);validate(binding,response,clock.instant());
        var observed=intake.observe(binding,response);
        if(observed.quarantined())return new View(Status.QUARANTINED,observed.acceptedIntentId(),false);
        if(observed.cancelled())return new View(Status.CANCEL_PENDING,observed.acceptedIntentId(),observed.acceptedIntentId()!=null);
        if(observed.confirmation().currentRevision()!=response.currentRevision())return new View(Status.CONFIRMATION_UNKNOWN,null,false);
        var done=terminal(stored.state(),observed,true);if(done!=null)return done;
        owned(stored.state(),owner,clock.instant());
        if(response.currentState()==State.REJECTED) {
            preparations.apply(binding.identity(),new Reject(owner,new Rejection(binding.identity(),response.rejectionId(),response.issuedAt())));
            return new View(Status.REJECTED,null,false);
        }
        var receipt=new Receipt(binding.identity(),response.confirmationId(),response.confirmedAt());
        var withReceipt=preparations.apply(binding.identity(),new Confirm(owner,receipt));
        if(withReceipt.state().quarantined())return new View(Status.QUARANTINED,null,false);
        require(modes.modeFor(binding.identity().tenantId())==AwardDispatchModeRouter.DeliveryMode.CENTER);
        String intentId=UUID.randomUUID().toString();
        preparations.apply(binding.identity(),new Accept(owner,intentId,admission));
        intake.hold(binding,intentId,clock.instant());
        // 最后落库可能等待其他索引锁，首次本地受理提交前再检原始风险/当前确认响应水位。
        owned(stored.state(),owner,clock.instant());window(admission.riskIssuedAt(),admission.riskExpiresAt(),clock.instant());validate(binding,response,clock.instant());
        return new View(Status.HELD_ACCEPTED,intentId,false);
    }
    private View terminal(ReferralAwardPreparation state,ReferralIntakeRepository.Context context,boolean replay) {
        require(context!=null);
        if(state.quarantined() || context.quarantined())return new View(Status.QUARANTINED,context.acceptedIntentId(),replay);
        if(context.cancelled())return new View(Status.CANCEL_PENDING,context.acceptedIntentId(),replay);
        if(state.phase()==Phase.ACCEPTED){require(Objects.equals(state.acceptedIntentId(),context.acceptedIntentId()));return new View(Status.HELD_ACCEPTED,state.replay(context.binding().identity()),replay);}
        if(state.phase()==Phase.REJECTED)return new View(Status.REJECTED,null,replay);
        if("REJECT".equals(context.riskAction()))return new View(Status.RISK_REJECTED,null,replay);
        return null;
    }
    private void validate(Binding binding,Result r,Instant now){ReferralIntakeProofs.confirmation(binding,r,now,proofLifetime);}
    private void window(Instant issued,Instant expires,Instant now){ReferralIntakeProofs.window(issued,expires,now,proofLifetime);}
    private static void owned(ReferralAwardPreparation s,Owner o,Instant now){require(o.owner().equals(s.lease().owner()) && o.fence()==s.lease().fence() && !now.isBefore(s.lease().acquiredAt()) && now.isBefore(s.lease().expiresAt()));}
    private static void require(boolean valid){if(!valid)throw rejected();}
    private static ConflictException rejected(){return new ConflictException("REFERRAL_INTAKE_UNAVAILABLE","referral held intake is unavailable or conflicts");}
    public enum Status { DISABLED,IN_PROGRESS,PENDING_RISK,RISK_REJECTED,CONFIRMATION_UNKNOWN,CANCEL_PENDING,QUARANTINED,REJECTED,HELD_ACCEPTED }
    /** HELD_ACCEPTED只是本地耐久保留，不是已发送、权益中心受理或履约成功。 */
    public record View(Status status,String intentId,boolean replay) { }
}
