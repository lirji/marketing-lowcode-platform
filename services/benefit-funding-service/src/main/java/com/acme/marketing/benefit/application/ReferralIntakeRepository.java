package com.acme.marketing.benefit.application;
import com.acme.marketing.benefit.application.ReferralIntakeIdentityPort.Binding;
import com.acme.marketing.benefit.application.ReferralIntakeConfirmationPort.Result;
import java.time.Instant;
/** V11专用HELD写模型，不与旧relay共享PENDING行；全部写入参加调用侧短事务。 */
public interface ReferralIntakeRepository {
    /** context首次冻结，重复只允许完全一致；原短授权窗口不被重签覆盖。 */
    Context reserve(Binding binding,Instant authorizationIssuedAt,Instant authorizationExpiresAt);
    Context lock(Binding binding);
    Context find(String tenant,String sourceRequestId);
    /** 保存完整当前确认水位；取消和冲突栅栏正常返回，不能以异常回滚观测。 */
    Context observe(Binding binding,Result result);
    void recordRisk(Binding binding,ReferralIntakeRiskPort.Decision decision);
    /** receipt、HELD意图、expected-fact与V10接受引用由应用层同事务编排。 */
    void hold(Binding binding,String intentId,Instant now);
    record Context(Binding binding,Instant authorizationIssuedAt,Instant authorizationExpiresAt,
            String riskAction,String riskDecisionId,Result confirmation,boolean quarantined,String acceptedIntentId) {
        public boolean cancelled(){return confirmation!=null && confirmation.currentState()==ReferralIntakeConfirmationPort.State.CANCEL_REQUESTED;}
        @Override public String toString(){return "ReferralIntakeContext[redacted]";}
    }
}
