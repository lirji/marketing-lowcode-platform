package com.acme.marketing.compiler.infrastructure.persistence;

import static com.acme.marketing.platform.time.SqlTime.format;

import com.acme.marketing.compiler.application.ArtifactStore;
import com.acme.marketing.compiler.infrastructure.persistence.mapper.ArtifactMapper;
import com.acme.marketing.compiler.infrastructure.persistence.mapper.ArtifactMapper.ArtifactRow;
import com.acme.marketing.contracts.artifact.ArtifactBundle;
import com.acme.marketing.platform.error.ConflictException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** 使用 MyBatis 持久化不可变编译制品的适配器。 */
@Repository
public class MybatisArtifactStore implements ArtifactStore {
    private final ArtifactMapper artifactMapper;
    private final ObjectMapper objectMapper;

    public MybatisArtifactStore(ArtifactMapper artifactMapper, ObjectMapper objectMapper) {
        this.artifactMapper = artifactMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 首次写入制品；重复标识只有在内容完全一致时才允许幂等返回。
     *
     * <p>数据库唯一键是跨进程竞争的最终裁决者，不能用先查后写替代。
     */
    @Override
    public ArtifactBundle putIfAbsent(ArtifactBundle artifact) {
        try {
            int inserted = artifactMapper.insert(toRow(artifact));
            if (inserted != 1) throw new IllegalStateException("保存编译制品的受影响行数不正确");
            return artifact;
        } catch (DuplicateKeyException duplicate) {
            ArtifactBundle stored = find(artifact.tenantId(), artifact.artifactId()).orElseThrow();
            if (!stored.checksum().equals(artifact.checksum())
                    || !stored.sourceDigest().equals(artifact.sourceDigest())
                    || !stored.signature().equals(artifact.signature())) {
                throw new ConflictException("ARTIFACT_ID_COLLISION", "content-addressed artifact identity collided");
            }
            return stored;
        }
    }

    /** 按租户和制品标识查询编译结果。 */
    @Override
    public Optional<ArtifactBundle> find(String tenantId, String artifactId) {
        return Optional.ofNullable(artifactMapper.select(tenantId, artifactId)).map(this::toBundle);
    }

    private ArtifactRow toRow(ArtifactBundle artifact) {
        return new ArtifactRow(artifact.tenantId(), artifact.artifactId(), artifact.definitionId(),
                artifact.definitionVersion(), artifact.type(), artifact.abi(), artifact.payload(), artifact.checksum(),
                artifact.sourceDigest(), artifact.signatureKeyId(), artifact.signature(), json(artifact.metadata()),
                format(artifact.compiledAt()));
    }

    private ArtifactBundle toBundle(ArtifactRow row) {
        return new ArtifactBundle(row.artifactId(), row.tenantId(), row.definitionId(), row.definitionVersion(),
                row.typeName(), row.abi(), row.payload(), row.checksum(), row.sourceDigest(), row.signatureKeyId(),
                row.signatureValue(), read(row.metadataJson()), Instant.parse(row.compiledAt()));
    }

    private String json(Map<String, String> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException failure) {
            throw new IllegalArgumentException("编译制品元数据无法序列化", failure);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> read(String value) {
        try {
            return objectMapper.readValue(value, Map.class);
        } catch (JacksonException failure) {
            throw new IllegalStateException("数据库中的编译制品元数据无效", failure);
        }
    }
}
