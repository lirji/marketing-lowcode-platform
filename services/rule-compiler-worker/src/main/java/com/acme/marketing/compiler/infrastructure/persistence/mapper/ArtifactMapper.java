package com.acme.marketing.compiler.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 编译制品数据库 Mapper；SQL 统一维护在同名 XML 中。 */
@Mapper
public interface ArtifactMapper {

    /** 插入不可变编译制品。 */
    int insert(ArtifactRow artifact);

    /** 按租户和制品标识查询。 */
    ArtifactRow select(@Param("tenantId") String tenantId, @Param("artifactId") String artifactId);

    /** 编译制品数据库读写模型。 */
    record ArtifactRow(String tenantId, String artifactId, String definitionId, long definitionVersion,
            String typeName, String abi, byte[] payload, String checksum, String sourceDigest,
            String signatureKeyId, String signatureValue, String metadataJson, String compiledAt) {
        public ArtifactRow {
            payload = payload.clone();
        }

        @Override
        public byte[] payload() {
            return payload.clone();
        }
    }
}
