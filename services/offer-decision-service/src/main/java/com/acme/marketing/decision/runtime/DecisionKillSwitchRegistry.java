package com.acme.marketing.decision.runtime;

import com.acme.marketing.contracts.release.KillSwitchDirective;
import com.acme.marketing.contracts.release.KillSwitchDirectiveSigner;
import com.acme.marketing.decision.runtime.DecisionKillSwitchStore.StoredDirective;
import com.acme.marketing.decision.runtime.DecisionKillSwitchStore.StoredState;
import com.acme.marketing.platform.error.ConflictException;
import jakarta.annotation.PostConstruct;
import java.security.PublicKey;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Verified emergency state is durable in MySQL and served from an in-process hot cache. */
public class DecisionKillSwitchRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger(DecisionKillSwitchRegistry.class);
    private final DecisionKillSwitchStore store;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Map<String, PublicKey> trustedKeys;
    private final String namespace;
    private final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();

    public DecisionKillSwitchRegistry(DecisionKillSwitchStore store, ObjectMapper objectMapper, Clock clock,
            Map<String, PublicKey> trustedKeys, String namespace) {
        this.store = store;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.trustedKeys = Map.copyOf(trustedKeys);
        this.namespace = namespace;
    }

    @Transactional
    public void apply(KillSwitchDirective directive) {
        verify(directive);
        State current = store.lock(directive.tenantId().value(), namespace).map(DecisionKillSwitchRegistry::state)
                .orElse(null);
        if (current != null && directive.switchSequence() <= current.sequence()) {
            if (directive.switchSequence() == current.sequence()
                    && !directive.signature().equals(current.signature())) {
                throw new ConflictException("KILL_SWITCH_SEQUENCE_CONFLICT", "kill switch sequence was reused");
            }
            states.put(directive.tenantId().value(), current);
            return;
        }
        if (current == null) {
            boolean inserted = store.tryInsert(directive.tenantId().value(), namespace, directive.switchSequence(),
                    directive.enabled(), directive.reason(), directive.signature(), json(directive),
                    directive.activatedAt());
            if (!inserted) {
                throw new ConflictException("KILL_SWITCH_CONCURRENT_UPDATE", "kill switch changed concurrently");
            }
        } else {
            store.update(directive.tenantId().value(), namespace, directive.switchSequence(), directive.enabled(),
                    directive.reason(), directive.signature(), json(directive), directive.activatedAt());
        }
        states.put(directive.tenantId().value(), new State(directive.switchSequence(), directive.signature(),
                directive.enabled(), directive.reason()));
    }

    public void requireEnabled(String tenantId) {
        State state = states.get(tenantId);
        if (state != null && state.enabled()) {
            throw new ConflictException("MARKETING_KILL_SWITCH_ENABLED", state.reason());
        }
    }

    @PostConstruct
    @Scheduled(fixedDelayString = "${marketing.kill-switch.reconcile-interval-ms:1000}")
    public void reload() {
        for (StoredDirective stored : store.findDirectives(namespace)) {
            try {
                KillSwitchDirective directive = objectMapper.readValue(stored.directiveJson(), KillSwitchDirective.class);
                verify(directive);
                states.compute(stored.tenantId(), (ignored, current) -> current == null
                        || directive.switchSequence() > current.sequence()
                        ? new State(directive.switchSequence(), directive.signature(), directive.enabled(),
                                directive.reason()) : current);
            } catch (Exception invalid) {
                LOGGER.error("rejected persisted decision kill-switch for tenant {}; retaining last-known-good",
                        stored.tenantId(), invalid);
            }
        }
    }

    private void verify(KillSwitchDirective directive) {
        PublicKey key = trustedKeys.get(directive.signatureKeyId());
        if (key == null || !KillSwitchDirectiveSigner.verify(key, directive)) {
            throw new ConflictException("KILL_SWITCH_SIGNATURE_INVALID", "kill switch directive is not trusted");
        }
        if (!namespace.equals(directive.namespace())
                || directive.activatedAt().isAfter(clock.instant().plusSeconds(60))) {
            throw new ConflictException("KILL_SWITCH_SCOPE_INVALID", "kill switch scope or time is invalid");
        }
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JacksonException failure) { throw new IllegalStateException("kill switch cannot be serialized", failure); }
    }

    private static State state(StoredState stored) {
        return new State(stored.sequence(), stored.signature(), stored.enabled(), stored.reason());
    }

    private record State(long sequence, String signature, boolean enabled, String reason) { }
}
