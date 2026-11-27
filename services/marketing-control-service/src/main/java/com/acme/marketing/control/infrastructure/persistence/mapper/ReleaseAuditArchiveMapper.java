package com.acme.marketing.control.infrastructure.persistence.mapper;

import com.acme.marketing.platform.web.archive.AuditArchiveWorkflow.Event;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 同租户活动版本的已核实发布证据读取，原控制审计链继续由控制面持有。 */
@Mapper
public interface ReleaseAuditArchiveMapper {
    List<Event> eligible(@Param("tenant") String tenant, @Param("campaign") String campaign,
                         @Param("version") long version, @Param("cutoff") Instant cutoff, @Param("limit") int limit);
}
