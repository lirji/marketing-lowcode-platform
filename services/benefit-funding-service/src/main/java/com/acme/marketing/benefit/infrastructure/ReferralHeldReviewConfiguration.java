package com.acme.marketing.benefit.infrastructure;
import com.acme.marketing.benefit.application.*;
import java.time.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;
/** 内部复核默认关闭，不注册定时worker；取消回流与真正投递开关分离。 */
@Configuration
public class ReferralHeldReviewConfiguration {
    /** 0仅关闭哨兵，实际证明寿命必须由已确认部署配置提供。 */
    @Bean public ReferralHeldReviewService referralHeldReviewService(ReferralPreparationRepository preparations,ReferralIntakeRepository intake,ReferralHeldReviewRepository reviews,
            ReferralIntakeIdentityPort identities,ReferralIntakeConfirmationPort confirmations,ReferralIntakeRiskPort risk,ReferralCandidateSnapshotService protection,
            AwardDispatchModeRouter modes,Clock clock,PlatformTransactionManager manager,@Value("${marketing.referral-held-review.enabled:false}")boolean enabled,
            @Value("${marketing.referral-held-review.proof-max-seconds:0}")long maximum) {
        return new ReferralHeldReviewService(preparations,intake,reviews,identities,confirmations,risk,protection,modes,clock,manager,enabled,Duration.ofSeconds(maximum));
    }
}
