package com.acme.marketing.benefit.infrastructure.persistence.mapper;

import com.acme.marketing.benefit.application.BenefitOutboxRepository;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 权益业务 Outbox MyBatis Mapper；有序领取和结果回写 SQL 统一位于 XML。 */
@Mapper
public interface BenefitOutboxMapper {
    List<BenefitOutboxRepository.PendingEvent> selectPublishable(@Param("now") String now,
            @Param("limit") int limit);
    int updatePublished(@Param("tenantId") String tenantId, @Param("eventId") String eventId,
            @Param("publishedAt") String publishedAt, @Param("attempts") int attempts);
    int updateDead(@Param("tenantId") String tenantId, @Param("eventId") String eventId,
            @Param("deadAt") String deadAt, @Param("attempts") int attempts, @Param("error") String error);
    int updateRetry(@Param("tenantId") String tenantId, @Param("eventId") String eventId,
            @Param("nextAttemptAt") String nextAttemptAt, @Param("attempts") int attempts,
            @Param("error") String error);
}
