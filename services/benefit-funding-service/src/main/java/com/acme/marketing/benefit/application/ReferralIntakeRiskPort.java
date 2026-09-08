package com.acme.marketing.benefit.application;
import com.acme.marketing.benefit.domain.ReferralAwardPreparation.Identity;
import java.time.Instant;
/** 独立裂变风险边界，不复用旧Drools永久首次结果规则；默认不可用。 */
public interface ReferralIntakeRiskPort {
    /** payload只在事务外、受保护内存中使用；禁止日志输出或普通body覆盖风控上下文。 */
    Decision evaluate(ReferralIntakeIdentityPort.Binding binding,ReferralCandidateSnapshotService.FrozenPayload payload);
    enum Action { ALLOW,REJECT,REVIEW,CHALLENGE,UNAVAILABLE }
    /** 可信适配器验证后结果，窗口和Identity必须绑定本次候选；record本身不是验签标记。 */
    record Decision(Identity identity,Action action,String decisionId,Instant issuedAt,Instant expiresAt) { }
}
