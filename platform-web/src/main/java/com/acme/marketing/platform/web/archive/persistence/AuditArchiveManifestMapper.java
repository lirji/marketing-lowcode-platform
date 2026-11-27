package com.acme.marketing.platform.web.archive.persistence;

import com.acme.marketing.platform.web.archive.AuditArchiveWorkflow.Batch;
import com.acme.marketing.platform.web.archive.AuditArchiveWorkflow.Grant;
import com.acme.marketing.platform.web.archive.AuditArchiveWorkflow.Repository;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 同一档案清单协议由资金和发布服务各自在自己的 schema 上使用。 */
@Mapper
public interface AuditArchiveManifestMapper extends Repository {
    @Override
    void claim(@Param("batch") Batch batch);

    @Override
    Batch byDigest(@Param("tenant") String tenant, @Param("resourceId") String resourceId, @Param("digest") String digest);

    @Override
    Batch batch(@Param("tenant") String tenant, @Param("batchId") String batchId);

    @Override
    Batch pending(@Param("tenant") String tenant);

    @Override
    void uploaded(@Param("tenant") String tenant, @Param("batchId") String batchId, @Param("version") String version);

    @Override
    void verified(@Param("tenant") String tenant, @Param("batchId") String batchId, @Param("version") String version);

    @Override
    Grant grant(@Param("tenant") String tenant, @Param("ticket") String ticket, @Param("actor") String actor,
                @Param("lock") boolean lock);

    @Override
    void storeRestored(@Param("tenant") String tenant, @Param("grant") Grant grant, @Param("payload") String payload);

    @Override
    String restored(@Param("tenant") String tenant, @Param("ticket") String ticket, @Param("actor") String actor,
                    @Param("batchId") String batchId);

    @Override
    int cleanupRestored(@Param("tenant") String tenant);
}
