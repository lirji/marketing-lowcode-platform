package com.acme.marketing.journeyservice.infrastructure.persistence.mapper;

import com.acme.marketing.journeyservice.application.JourneyExecutionProbeRepository.Key;
import com.acme.marketing.journeyservice.application.JourneyProbeDeliveryRepository.*;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 投递任务SQL固定在XML；领取只持有工作行，不在事务内访问Kafka。 */
@Mapper
public interface JourneyProbeDeliveryMapper {
    /** 重复排队保留原目标和进度。 */
    int ensure(@Param("key") Key key, @Param("value") NewDelivery value);
    /** 按完整业务键取一条任务。 */
    Delivery find(@Param("key") Key key);
    /** 跳过其他进程锁定的行，不等待其网络发送。 */
    Delivery lockDue(@Param("slot") Slot slot, @Param("now") String now);
    /** CAS争取新租约代次，不能接管仍有效的领取。 */
    int claim(@Param("key") Key key, @Param("expectedEpoch") long expectedEpoch, @Param("owner") String owner,
            @Param("leaseUntil") String leaseUntil, @Param("now") String now);
    /** 只接受当前领取者且租约未到期的状态推进。 */
    int finish(@Param("key") Key key, @Param("lease") Lease lease, @Param("completion") Completion completion, @Param("now") String now);
    /** 每次有界退休过期任务，不删除原证据。 */
    int expireDue(@Param("slot") Slot slot, @Param("now") String now, @Param("limit") int limit, @Param("errorCode") String errorCode);
}
