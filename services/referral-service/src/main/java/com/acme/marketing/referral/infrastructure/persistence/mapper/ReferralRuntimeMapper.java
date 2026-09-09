package com.acme.marketing.referral.infrastructure.persistence.mapper;

import com.acme.marketing.referral.application.release.ReferralReleaseRepository.Stored;
import com.acme.marketing.referral.application.release.ReferralRuntimeRepository.Cursor;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;

/** 指令游标与审计只能通过同一本地事务变更。 */
@Mapper
public interface ReferralRuntimeMapper {
    /** 读取不可变制品。 */
    Stored installed(Map<String, Object> values);
    /** 保留流主键，不覆盖历史。 */
    int reserve(Map<String, Object> values);
    /** 获取排他锁，串行决定序号。 */
    Cursor lock(Map<String, Object> values);
    /** 恢复读只返回已提交状态。 */
    Cursor current(Map<String, Object> values);
    /** 追加指令永久事实，重复序号不得覆盖。 */
    int append(Map<String, Object> values);
    /** 锁内仍使用旧序号条件，阻止错误调用越过竞争检查。 */
    int advance(Map<String, Object> values);
}
