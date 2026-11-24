package com.acme.marketing.audience.application;

import java.time.Instant;
import java.util.List;

/** 受众用例依赖已授权的商城画像，传输和凭据属于适配器。 */
public interface CommerceProfileSource {
    boolean supports(String tenant);

    SourceSnapshot read(String tenant);

    /** 优先返回等级汇总，兼容未实现汇总的商城版本。 */
    EstimateObservation readEstimate(String tenant);

    sealed interface EstimateObservation permits SourceSnapshot, LevelSummary {
        Instant asOf();
    }

    record SourceSnapshot(Instant asOf, List<Profile> profiles) implements EstimateObservation { }
    record LevelSummary(Instant asOf, List<LevelCount> levels) implements EstimateObservation { }
    record LevelCount(String memberLevel, Long memberCount) { }
    record Profile(String memberId, String memberLevel) { }
}
