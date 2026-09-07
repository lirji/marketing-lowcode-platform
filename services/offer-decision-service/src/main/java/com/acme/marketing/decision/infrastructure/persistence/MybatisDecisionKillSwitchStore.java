package com.acme.marketing.decision.infrastructure.persistence;

import static com.acme.marketing.platform.time.SqlTime.format;

import com.acme.marketing.decision.infrastructure.persistence.mapper.DecisionMapper;
import com.acme.marketing.decision.infrastructure.persistence.mapper.DecisionMapper.KillSwitchDirectiveRow;
import com.acme.marketing.decision.infrastructure.persistence.mapper.DecisionMapper.KillSwitchStateRow;
import com.acme.marketing.decision.infrastructure.persistence.mapper.DecisionMapper.KillSwitchWrite;
import com.acme.marketing.decision.runtime.DecisionKillSwitchStore;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

/** 基于 MyBatis 的决策紧急开关持久化适配器。 */
@Repository
public class MybatisDecisionKillSwitchStore implements DecisionKillSwitchStore {
    private final DecisionMapper mapper;

    public MybatisDecisionKillSwitchStore(DecisionMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<StoredState> lock(String tenantId, String namespace) {
        KillSwitchStateRow row = mapper.selectKillSwitchForUpdate(tenantId, namespace);
        return row == null ? Optional.empty()
                : Optional.of(new StoredState(row.switchSequence(), row.directiveSignature(),
                        row.enabledValue(), row.reasonText()));
    }

    @Override
    public boolean tryInsert(String tenantId, String namespace, long sequence, boolean enabled, String reason,
            String signature, String directiveJson, Instant updatedAt) {
        try {
            return mapper.insertKillSwitch(write(tenantId, namespace, sequence, enabled, reason, signature,
                    directiveJson, updatedAt)) == 1;
        } catch (DuplicateKeyException race) {
            return false;
        }
    }

    @Override
    public void update(String tenantId, String namespace, long sequence, boolean enabled, String reason,
            String signature, String directiveJson, Instant updatedAt) {
        int updated = mapper.updateKillSwitch(write(tenantId, namespace, sequence, enabled, reason, signature,
                directiveJson, updatedAt));
        if (updated != 1) throw new IllegalStateException("更新决策紧急开关的受影响行数不正确");
    }

    @Override
    public List<StoredDirective> findDirectives(String namespace) {
        return mapper.selectKillSwitchDirectives(namespace).stream()
                .map(MybatisDecisionKillSwitchStore::toStoredDirective).toList();
    }

    private static KillSwitchWrite write(String tenantId, String namespace, long sequence, boolean enabled,
            String reason, String signature, String directiveJson, Instant updatedAt) {
        return new KillSwitchWrite(tenantId, namespace, sequence, enabled, reason, signature, directiveJson,
                format(updatedAt));
    }

    private static StoredDirective toStoredDirective(KillSwitchDirectiveRow row) {
        return new StoredDirective(row.tenantId(), row.directiveJson());
    }
}
