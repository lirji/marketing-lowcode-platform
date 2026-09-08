package com.acme.marketing.referral.infrastructure.persistence.mapper;

import com.acme.marketing.referral.application.fanout.ReferralFanoutRepository.Current;
import com.acme.marketing.referral.application.fanout.ReferralFanoutRepository.Target;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;

/** fanout租约与稳定分页SQL入口，禁止以外键插入隐式锁定父关系。 */
@Mapper
public interface ReferralFanoutMapper {
    TaskRow next(Map<String,Object> values);
    TaskRow task(Map<String,Object> values);
    Current current(Map<String,Object> values);
    int claim(Map<String,Object> values);
    List<Target> targets(Map<String,Object> values);
    int finish(Map<String,Object> values);
    /** 时间按UTC微秒文本往返，避免JDBC默认时区解释租约。 */
    record TaskRow(String tenantId,String resourceId,long desiredVersion,String reason,String status,String cursor,
            String owner,String leaseUntil,long fence,long rowVersion) {
        @Override public String toString(){return "FanoutPersistenceRow[redacted]";}
    }
}
