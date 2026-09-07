package com.acme.marketing.journeyservice.application;

import static com.acme.marketing.platform.time.SqlTime.format;

import com.acme.marketing.contracts.release.KillSwitchDirective;
import com.acme.marketing.contracts.release.KillSwitchDirectiveSigner;
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

public class JourneyKillSwitchRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger(JourneyKillSwitchRegistry.class);
    private final JourneyRepository repository;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Map<String, PublicKey> trustedKeys;
    private final String namespace;
    private final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();

    public JourneyKillSwitchRegistry(JourneyRepository repository, ObjectMapper mapper, Clock clock,
            Map<String, PublicKey> trustedKeys, String namespace) {
        this.repository = repository;
        this.mapper = mapper;
        this.clock = clock;
        this.trustedKeys = Map.copyOf(trustedKeys);
        this.namespace = namespace;
    }

    @Transactional
    public void apply(KillSwitchDirective directive) {
        verify(directive);
        State current = repository.findKillSwitchForUpdate(directive.tenantId().value(), namespace)
                .map(row -> new State(row.switchSequence(), row.directiveSignature(), row.enabledValue(),
                        row.reasonText()))
                .orElse(null);
        if (current != null && directive.switchSequence() <= current.sequence()) {
            if (directive.switchSequence() == current.sequence()
                    && !directive.signature().equals(current.signature())) {
                throw new ConflictException("KILL_SWITCH_SEQUENCE_CONFLICT", "kill switch sequence was reused");
            }
            states.put(directive.tenantId().value(), current);
            return;
        }
        JourneyRepository.KillSwitchWrite write = new JourneyRepository.KillSwitchWrite(
                directive.tenantId().value(), namespace, directive.switchSequence(), directive.enabled(),
                directive.reason(), directive.signature(), json(directive), format(directive.activatedAt()));
        if (current == null) {
            if (!repository.trySaveKillSwitch(write)) {
                throw new ConflictException("KILL_SWITCH_CONCURRENT_UPDATE", "kill switch changed concurrently");
            }
        } else {
            repository.updateKillSwitch(write);
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
        repository.findKillSwitches(namespace).forEach(row -> {
                    try {
                        KillSwitchDirective directive = mapper.readValue(row.directiveJson(), KillSwitchDirective.class);
                        verify(directive);
                        states.compute(row.tenantId(), (ignored, current) -> current == null
                                || directive.switchSequence() > current.sequence()
                                ? new State(directive.switchSequence(), directive.signature(), directive.enabled(),
                                        directive.reason()) : current);
                    } catch (Exception invalid) {
                        LOGGER.error("rejected persisted journey kill-switch for tenant {}; retaining last-known-good",
                                row.tenantId(), invalid);
                    }
                });
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
        try { return mapper.writeValueAsString(value); }
        catch (JacksonException failure) { throw new IllegalStateException("kill switch cannot be serialized", failure); }
    }

    private record State(long sequence, String signature, boolean enabled, String reason) { }
}
