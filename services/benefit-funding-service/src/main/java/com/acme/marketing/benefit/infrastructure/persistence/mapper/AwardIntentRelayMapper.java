package com.acme.marketing.benefit.infrastructure.persistence.mapper;

import com.acme.marketing.benefit.application.AwardIntentRelayRepository;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 发奖 Relay MyBatis Mapper；候选公平性和租约 CAS SQL 统一位于 XML。 */
@Mapper
public interface AwardIntentRelayMapper {
    List<AwardIntentRelayRepository.PendingIntent> selectCandidates(@Param("now") String now,
            @Param("tenantLimit") int tenantLimit, @Param("totalLimit") int totalLimit);
    int claim(AwardIntentRelayRepository.ClaimWrite write);
    int updateSent(AwardIntentRelayRepository.SentWrite write);
    int updateFailure(AwardIntentRelayRepository.FailureWrite write);
}
