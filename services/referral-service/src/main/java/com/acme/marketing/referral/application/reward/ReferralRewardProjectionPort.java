package com.acme.marketing.referral.application.reward;

import com.acme.marketing.referral.ReferralPlan;
import com.acme.marketing.referral.application.qualification.ReferralQualificationPermitPort.Binding;
import com.acme.marketing.referral.application.qualification.ReferralQualificationRepository.Qualification;
import com.acme.marketing.referral.domain.qualification.ReferralProgressTransition.Progress;
import java.time.Instant;

/** 资格拥有的事务扩展点：人数、永久奖励与待办必须一并提交，不在此处签名或发券。 */
public interface ReferralRewardProjectionPort {
    /** 调用前已按锚点→参与者→关系→资格/人数→订单→任务加锁；实现禁止网络和新事务。 */
    void reconcile(Binding binding,ReferralPlan plan,Qualification qualification,Progress progress,String actor,String trace,Instant now);
}
