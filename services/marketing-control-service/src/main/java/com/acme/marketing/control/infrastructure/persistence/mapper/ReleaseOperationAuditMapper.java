package com.acme.marketing.control.infrastructure.persistence.mapper;

import com.acme.marketing.control.application.ReleaseOperationAudit.Entry;
import com.acme.marketing.control.application.ReleaseOperationAudit.Write;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 发布留痕仅追加，限定来源资源及活动归属读取。 */
@Mapper
public interface ReleaseOperationAuditMapper {
    int append(Write write);

    Long original(@Param("tenant") String tenant, @Param("resourceId") String resourceId);

    List<Entry> history(@Param("tenant") String tenant, @Param("campaignId") String campaignId,
            @Param("organizationId") String organizationId, @Param("shopId") String shopId,
            @Param("before") Long before, @Param("limit") int limit);
}
