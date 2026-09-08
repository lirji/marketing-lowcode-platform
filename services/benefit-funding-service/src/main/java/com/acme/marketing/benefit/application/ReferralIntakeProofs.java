package com.acme.marketing.benefit.application;
import com.acme.marketing.benefit.application.ReferralIntakeIdentityPort.Binding;
import com.acme.marketing.benefit.application.ReferralIntakeConfirmationPort.*;
import com.acme.marketing.platform.error.ConflictException;
import java.time.*;
/** 新裂变路径共享的可信响应形状与原始时限校验；不执行网络或自行证明来源可信。 */
public final class ReferralIntakeProofs {
    private ReferralIntakeProofs(){}
    /** 适配器验来源之后仍需核完整身份、永久回执和当前取消水位。 */
    public static void confirmation(Binding binding,Result r,Instant now,Duration maximum) {
        require(binding.identity().equals(r.identity()) && r.currentState()!=null && r.currentState()!=State.UNKNOWN && r.currentRevision()>0 && r.cancelRevision()>=0);
        window(r.issuedAt(),r.expiresAt(),now,maximum);
        if(r.currentState()==State.CONFIRMED || r.currentState()==State.CANCEL_REQUESTED)
            require(r.confirmationId()!=null && !r.confirmationId().isBlank() && r.authorizationSequence()>0 && r.confirmedAt()!=null && !r.confirmedAt().isAfter(now));
        else require(r.rejectionId()!=null && !r.rejectionId().isBlank() && r.confirmationId()==null && r.confirmedAt()==null && r.authorizationSequence()==0);
        require(r.currentState()==State.CANCEL_REQUESTED?r.cancelRevision()>0 && r.cancelRevision()<=r.currentRevision():r.cancelRevision()==0);
    }
    /** 不截微秒；数据库锁等待结束后及首次写入提交前必须重新调用。 */
    public static void window(Instant issued,Instant expires,Instant now,Duration maximum){require(issued!=null && expires!=null && !now.isBefore(issued) && now.isBefore(expires) && issued.isBefore(expires) && Duration.between(issued,expires).compareTo(maximum)<=0);}
    private static void require(boolean valid){if(!valid)throw new ConflictException("REFERRAL_INTAKE_UNAVAILABLE","referral held intake is unavailable or conflicts");}
}
