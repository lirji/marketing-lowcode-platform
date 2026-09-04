package com.acme.marketing.compiler.infrastructure;

import static com.acme.marketing.platform.time.SqlTime.format;

import com.acme.marketing.compiler.application.ArtifactStore;
import com.acme.marketing.contracts.artifact.ArtifactBundle;
import com.acme.marketing.platform.error.ConflictException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcArtifactStore implements ArtifactStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcArtifactStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public ArtifactBundle putIfAbsent(ArtifactBundle artifact) {
        try {
            jdbc.update("insert into mk_compiled_artifact(tenant_id,artifact_id,definition_id,definition_version,type_name,abi,payload,checksum,source_digest,signature_key_id,signature_value,metadata_json,compiled_at) values(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    artifact.tenantId(), artifact.artifactId(), artifact.definitionId(), artifact.definitionVersion(),
                    artifact.type(), artifact.abi(), artifact.payload(), artifact.checksum(), artifact.sourceDigest(),
                    artifact.signatureKeyId(), artifact.signature(), json(artifact.metadata()),
                    format(artifact.compiledAt()));
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

    @Override
    public Optional<ArtifactBundle> find(String tenantId, String artifactId) {
        List<ArtifactBundle> rows = jdbc.query("select definition_id,definition_version,type_name,abi,payload,checksum,source_digest,signature_key_id,signature_value,metadata_json,compiled_at from mk_compiled_artifact where tenant_id=? and artifact_id=?",
                (rs, rowNum) -> new ArtifactBundle(artifactId, tenantId, rs.getString(1), rs.getLong(2),
                        rs.getString(3), rs.getString(4), rs.getBytes(5), rs.getString(6), rs.getString(7),
                        rs.getString(8), rs.getString(9), read(rs.getString(10)), Instant.parse(rs.getString(11))),
                tenantId, artifactId);
        return rows.stream().findFirst();
    }

    private String json(Map<String, String> value) {
        try { return mapper.writeValueAsString(value); }
        catch (JacksonException failure) { throw new IllegalArgumentException("artifact metadata is invalid", failure); }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> read(String value) {
        try { return mapper.readValue(value, Map.class); }
        catch (JacksonException failure) { throw new IllegalStateException("stored artifact metadata is invalid", failure); }
    }
}
