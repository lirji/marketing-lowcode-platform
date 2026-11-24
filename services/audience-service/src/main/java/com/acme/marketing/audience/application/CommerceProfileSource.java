package com.acme.marketing.audience.application;

import java.time.Instant;
import java.util.List;

/** 受众用例只依赖已授权的完整画像视图，传输和凭据属于适配器。 */
public interface CommerceProfileSource {
    boolean supports(String tenant);

    SourceSnapshot read(String tenant);

    record SourceSnapshot(Instant asOf, List<Profile> profiles) { }
    record Profile(String memberId, String memberLevel) { }
}
