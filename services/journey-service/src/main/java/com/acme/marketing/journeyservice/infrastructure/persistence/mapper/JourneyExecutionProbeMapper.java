package com.acme.marketing.journeyservice.infrastructure.persistence.mapper;

import com.acme.marketing.journeyservice.application.JourneyExecutionProbeRepository.Key;
import com.acme.marketing.journeyservice.application.JourneyExecutionProbeRepository.StoredProbe;
import com.acme.marketing.journeyservice.application.JourneyExecutionProbeRepository.StoredAck;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** SQL统一在XML，查询不能省略租户、本站位置或挑战键。 */
@Mapper
public interface JourneyExecutionProbeMapper {
    /** 并发首次创建由主键仲裁，重复不修改原内容。 */
    int ensure(@Param("key") Key key, @Param("value") StoredProbe value);
    /** 获取一致锁定行。 */
    StoredProbe lock(@Param("key") Key key);
    /** 有序有界读取每个实际子任务的第一份回执。 */
    List<StoredAck> acknowledgements(@Param("key") Key key);
    /** 调用者持有父行锁，数据库子键继续防止重复下标。 */
    int saveAck(@Param("key") Key key, @Param("value") StoredAck value);
}
