package com.acme.marketing.decision.infrastructure.persistence;

import static com.acme.marketing.platform.time.SqlTime.format;

import com.acme.marketing.contracts.release.ActivationDirective;
import com.acme.marketing.contracts.release.ReleaseManifest;
import com.acme.marketing.decision.infrastructure.persistence.mapper.DecisionMapper;
import com.acme.marketing.decision.infrastructure.persistence.mapper.DecisionMapper.DesiredPointerRow;
import com.acme.marketing.decision.infrastructure.persistence.mapper.DecisionMapper.RuntimeGenerationKey;
import com.acme.marketing.decision.infrastructure.persistence.mapper.DecisionMapper.RuntimeGenerationRow;
import com.acme.marketing.decision.infrastructure.persistence.mapper.DecisionMapper.RuntimeGenerationWrite;
import com.acme.marketing.decision.infrastructure.persistence.mapper.DecisionMapper.RuntimeSlotKey;
import com.acme.marketing.decision.infrastructure.persistence.mapper.DecisionMapper.RuntimeSlotQuery;
import com.acme.marketing.decision.infrastructure.persistence.mapper.DecisionMapper.RuntimeSlotWrite;
import com.acme.marketing.decision.infrastructure.persistence.mapper.DecisionMapper.SlotPointerRow;
import com.acme.marketing.decision.runtime.RuntimeManifestRegistry.RuntimeSlot;
import com.acme.marketing.decision.runtime.RuntimeStateStore;
import com.acme.marketing.platform.error.ConflictException;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** 基于 MyBatis 的决策运行时期望状态持久化适配器。 */
@Repository
public class MybatisRuntimeStateStore implements RuntimeStateStore {
    private static final String RUNTIME_NAME = "decision";

    private final DecisionMapper decisionMapper;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public MybatisRuntimeStateStore(DecisionMapper decisionMapper, ObjectMapper objectMapper, Clock clock) {
        this.decisionMapper = decisionMapper;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void install(String releaseKeyId, ReleaseManifest manifest, byte[] artifactPayload) {
        try {
            decisionMapper.insertRuntimeGeneration(new RuntimeGenerationWrite(manifest.tenantId().value(),
                    manifest.environment(), manifest.cell(), manifest.runtime(), manifest.namespace(),
                    manifest.generation(), releaseKeyId, json(manifest), artifactPayload, manifest.signature(),
                    format(clock.instant())));
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
        SlotPointerRow current = pointer(directive, true);
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
        int updated = decisionMapper.updateRuntimeSlot(new RuntimeSlotWrite(directive.tenantId().value(),
                directive.environment(), directive.cell(), directive.runtime(), directive.namespace(),
                directive.generation(), directive.activationSequence(), directive.signature(), json(directive),
                format(clock.instant())));
        if (updated != 1) throw new IllegalStateException("更新决策运行时槽位的受影响行数不正确");
    }

    @Override
    public Optional<DesiredState> desired(RuntimeSlot slot, String tenantId) {
        SlotPointerRow pointer = decisionMapper.selectRuntimeSlot(slotKey(slot, tenantId));
        if (pointer == null || pointer.desiredGeneration() <= 0) return Optional.empty();
        return generation(slot, tenantId, pointer.desiredGeneration())
                .map(stored -> new DesiredState(stored, read(pointer.directiveJson(), ActivationDirective.class)));
    }

    @Override
    public List<DesiredPointer> desiredPointers(RuntimeSlot slot) {
        return decisionMapper.selectDesiredPointers(new RuntimeSlotQuery(slot.environment(), slot.cell(),
                slot.namespace())).stream().map(MybatisRuntimeStateStore::toDesiredPointer).toList();
    }

    @Override
    public Optional<StoredGeneration> generation(RuntimeSlot slot, String tenantId, long generation) {
        RuntimeGenerationRow row = decisionMapper.selectRuntimeGeneration(new RuntimeGenerationKey(tenantId,
                slot.environment(), slot.cell(), slot.namespace(), generation));
        return row == null ? Optional.empty()
                : Optional.of(new StoredGeneration(row.releaseKeyId(), read(row.manifestJson()),
                        row.artifactPayload()));
    }

    private void ensureSlot(ActivationDirective directive) {
        try {
            decisionMapper.insertRuntimeSlot(new RuntimeSlotWrite(directive.tenantId().value(),
                    directive.environment(), directive.cell(), directive.runtime(), directive.namespace(),
                    0, 0, "", "", format(clock.instant())));
        } catch (DuplicateKeyException alreadyExists) {
            // 后续加锁读取会按完整槽位主键串行化并发写入者。
        }
    }

    private SlotPointerRow pointer(ActivationDirective directive, boolean lock) {
        RuntimeSlotKey key = new RuntimeSlotKey(directive.tenantId().value(), directive.environment(),
                directive.cell(), directive.runtime(), directive.namespace());
        return lock ? decisionMapper.selectRuntimeSlotForUpdate(key) : decisionMapper.selectRuntimeSlot(key);
    }

    private static RuntimeSlotKey slotKey(RuntimeSlot slot, String tenantId) {
        return new RuntimeSlotKey(tenantId, slot.environment(), slot.cell(), RUNTIME_NAME, slot.namespace());
    }

    private static DesiredPointer toDesiredPointer(DesiredPointerRow row) {
        return new DesiredPointer(row.tenantId(), row.desiredGeneration(), row.activationSequence());
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException failure) {
            throw new IllegalStateException("运行时状态无法序列化", failure);
        }
    }

    private ReleaseManifest read(String value) {
        return read(value, ReleaseManifest.class);
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JacksonException failure) {
            throw new IllegalStateException("数据库中的运行时状态无效", failure);
        }
    }

    private static RuntimeSlot slot(ReleaseManifest manifest) {
        return new RuntimeSlot(manifest.environment(), manifest.cell(), manifest.namespace());
    }
}
