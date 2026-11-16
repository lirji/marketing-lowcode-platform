package com.acme.marketing.benefit.infrastructure.persistence.mapper;

import com.acme.marketing.benefit.application.AwardFulfillmentRepository.*;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 履约观察 SQL 只在配套 XML 中执行，不跨库读取权益中心表。 */
@Mapper
public interface AwardFulfillmentMapper {
    /** 读取带租户范围的原始出站身份。 */
    Intent selectIntent(@Param("tenantId") String tenantId, @Param("intentId") String intentId);
    /** 依靠唯一键合并初始化，允许与原意图同事务提交。 */
    int initialize(@Param("tenantId") String tenantId, @Param("intentId") String intentId, @Param("now") String now);
    /** 失效旧扫描候选并唤醒任务，不抢占有效租约。 */
    int refresh(@Param("tenantId") String tenantId, @Param("intentId") String intentId, @Param("now") String now);
    /** 按租户配额选取有界到期批次。 */
    List<Candidate> due(@Param("now") String now, @Param("tenantLimit") int tenantLimit, @Param("limit") int limit);
    /** 以版本与到期条件争抢独占查询权。 */
    int claim(Lease lease);
    /** 只有仍有效的当前租约能够记录结果。 */
    int complete(Completion completion);
    /** 读取最新观察与独立同步错误。 */
    Stored select(@Param("tenantId") String tenantId, @Param("intentId") String intentId);
}
