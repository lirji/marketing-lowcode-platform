package com.acme.marketing.decision.infrastructure;

import static com.acme.marketing.platform.time.SqlTime.format;

import com.acme.marketing.contracts.release.ActivationDirective;
import com.acme.marketing.contracts.release.ReleaseManifest;
import com.acme.marketing.decision.runtime.RuntimeManifestRegistry.RuntimeSlot;
import com.acme.marketing.decision.runtime.RuntimeStateStore;
import com.acme.marketing.platform.error.ConflictException;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcRuntimeStateStore implements RuntimeStateStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;

    public JdbcRuntimeStateStore(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void install(String releaseKeyId, ReleaseManifest manifest, byte[] artifactPayload) {
        try {
            jdbc.update("insert into mk_runtime_generation(tenant_id,environment_name,cell_id,runtime_name,namespace_name,generation_no,release_key_id,manifest_json,artifact_payload,manifest_signature,warmed_at) values(?,?,?,?,?,?,?,?,?,?,?)",
                    manifest.tenantId().value(), manifest.environment(), manifest.cell(), manifest.runtime(),
                    manifest.namespace(), manifest.generation(), releaseKeyId, json(manifest), artifactPayload,
                    manifest.signature(), format(clock.instant()));
        } catch (DuplicateKeyException duplicate) {
            StoredGeneration existing = generation(slot(manifest), manifest.tenantId().value(), manifest.generation())
                    .orElseThrow(() -> duplicate);
            if (!existing.manifest().signature().equals(manifest.signature())) {
                throw new ConflictException("MANIFEST_GENERATION_CONFLICT",
                        "generation already points at a different signed manifest");
            }
            if (!Arrays.equals(existing.artifactPayload(), artifactPayload)) {
                throw new ConflictException("MANIFEST_ARTIFACT_CONFLICT",
                        "installed generation contains different artifact bytes");
            }
        }
    }

    @Override
    @Transactional
    public void activate(ActivationDirective directive) {
        ensureSlot(directive);
        SlotPointer current = pointer(directive, true);
        if (directive.activationSequence() < current.activationSequence()) {
            throw new ConflictException("ACTIVATION_SEQUENCE_STALE", "activation directive is stale");
        }
        if (directive.activationSequence() == current.activationSequence() && current.activationSequence() > 0) {
            if (!directive.signature().equals(current.directiveSignature())) {
                throw new ConflictException("ACTIVATION_SEQUENCE_CONFLICT", "activation sequence was reused");
            }
            return;
        }
        RuntimeSlot runtimeSlot = new RuntimeSlot(directive.environment(), directive.cell(), directive.namespace());
        StoredGeneration installed = generation(runtimeSlot, directive.tenantId().value(), directive.generation())
                .orElseThrow(() -> new ConflictException("ACTIVATION_GENERATION_NOT_INSTALLED",
                        "activation references a generation that is not installed"));
        if (!installed.manifest().signature().equals(directive.manifestSignature())) {
            throw new ConflictException("ACTIVATION_MANIFEST_MISMATCH",
                    "activation does not bind the installed manifest");
        }
        jdbc.update("update mk_runtime_slot set desired_generation=?,activation_sequence=?,directive_signature=?,directive_json=?,updated_at=? where tenant_id=? and environment_name=? and cell_id=? and runtime_name=? and namespace_name=?",
                directive.generation(), directive.activationSequence(), directive.signature(), json(directive),
                format(clock.instant()), directive.tenantId().value(), directive.environment(), directive.cell(),
                directive.runtime(), directive.namespace());
    }

    @Override
    public Optional<DesiredState> desired(RuntimeSlot slot, String tenantId) {
        List<SlotPointer> pointers = jdbc.query("select desired_generation,activation_sequence,directive_signature,directive_json from mk_runtime_slot where tenant_id=? and environment_name=? and cell_id=? and runtime_name='decision' and namespace_name=?",
                (rs, rowNum) -> new SlotPointer(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4)), tenantId,
                slot.environment(), slot.cell(), slot.namespace());
        if (pointers.isEmpty() || pointers.getFirst().generation() <= 0) return Optional.empty();
        SlotPointer pointer = pointers.getFirst();
        return generation(slot, tenantId, pointer.generation())
                .map(stored -> new DesiredState(stored, read(pointer.directiveJson(), ActivationDirective.class)));
    }

    @Override
    public List<DesiredPointer> desiredPointers(RuntimeSlot slot) {
        return jdbc.query("select tenant_id,desired_generation,activation_sequence from mk_runtime_slot where environment_name=? and cell_id=? and runtime_name='decision' and namespace_name=? and desired_generation>0",
                (rs, rowNum) -> new DesiredPointer(rs.getString(1), rs.getLong(2), rs.getLong(3)),
                slot.environment(), slot.cell(), slot.namespace());
    }

    @Override
    public Optional<StoredGeneration> generation(RuntimeSlot slot, String tenantId, long generation) {
        List<StoredGeneration> rows = jdbc.query("select release_key_id,manifest_json,artifact_payload from mk_runtime_generation where tenant_id=? and environment_name=? and cell_id=? and runtime_name='decision' and namespace_name=? and generation_no=?",
                (rs, rowNum) -> new StoredGeneration(rs.getString(1), read(rs.getString(2)), rs.getBytes(3)),
                tenantId, slot.environment(), slot.cell(), slot.namespace(), generation);
        return rows.stream().findFirst();
    }

    private void ensureSlot(ActivationDirective directive) {
        try {
            jdbc.update("insert into mk_runtime_slot(tenant_id,environment_name,cell_id,runtime_name,namespace_name,desired_generation,activation_sequence,directive_signature,directive_json,updated_at) values(?,?,?,?,?,?,?,?,?,?)",
                    directive.tenantId().value(), directive.environment(), directive.cell(), directive.runtime(),
                    directive.namespace(), 0, 0, "", "", format(clock.instant()));
        } catch (DuplicateKeyException alreadyExists) {
            // Locked read below serializes writers for this complete runtime slot.
        }
    }

    private SlotPointer pointer(ActivationDirective directive, boolean lock) {
        return jdbc.query("select desired_generation,activation_sequence,directive_signature,directive_json from mk_runtime_slot where tenant_id=? and environment_name=? and cell_id=? and runtime_name=? and namespace_name=?" + (lock ? " for update" : ""),
                rs -> rs.next() ? new SlotPointer(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4)) : null,
                directive.tenantId().value(), directive.environment(), directive.cell(), directive.runtime(),
                directive.namespace());
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JacksonException failure) { throw new IllegalStateException("runtime state cannot be serialized", failure); }
    }

    private ReleaseManifest read(String value) {
        return read(value, ReleaseManifest.class);
    }

    private <T> T read(String value, Class<T> type) {
        try { return mapper.readValue(value, type); }
        catch (JacksonException failure) { throw new IllegalStateException("stored manifest is invalid", failure); }
    }

    private static RuntimeSlot slot(ReleaseManifest manifest) {
        return new RuntimeSlot(manifest.environment(), manifest.cell(), manifest.namespace());
    }

    private record SlotPointer(long generation, long activationSequence, String directiveSignature,
            String directiveJson) { }
}
