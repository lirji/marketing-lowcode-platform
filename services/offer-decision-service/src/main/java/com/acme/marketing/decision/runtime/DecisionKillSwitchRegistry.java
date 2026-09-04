package com.acme.marketing.decision.runtime;

import static com.acme.marketing.platform.time.SqlTime.format;

import com.acme.marketing.contracts.release.KillSwitchDirective;
import com.acme.marketing.contracts.release.KillSwitchDirectiveSigner;
import com.acme.marketing.platform.error.ConflictException;
import jakarta.annotation.PostConstruct;
import java.security.PublicKey;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Verified emergency state is durable in MySQL and served from an in-process hot cache. */
public class DecisionKillSwitchRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger(DecisionKillSwitchRegistry.class);
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Map<String, PublicKey> trustedKeys;
    private final String namespace;
    private final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();

    public DecisionKillSwitchRegistry(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock,
            Map<String, PublicKey> trustedKeys, String namespace) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
        this.trustedKeys = Map.copyOf(trustedKeys);
        this.namespace = namespace;
    }

    @Transactional
    public void apply(KillSwitchDirective directive) {
        verify(directive);
        List<State> rows = jdbc.query("select switch_sequence,directive_signature,enabled_value,reason_text from mk_decision_kill_switch where tenant_id=? and namespace_name=? for update",
                (rs, rowNum) -> new State(rs.getLong(1), rs.getString(2), rs.getBoolean(3), rs.getString(4)),
                directive.tenantId().value(), namespace);
        State current = rows.isEmpty() ? null : rows.getFirst();
        if (current != null && directive.switchSequence() <= current.sequence()) {
            if (directive.switchSequence() == current.sequence()
                    && !directive.signature().equals(current.signature())) {
                throw new ConflictException("KILL_SWITCH_SEQUENCE_CONFLICT", "kill switch sequence was reused");
            }
            states.put(directive.tenantId().value(), current);
            return;
        }
        if (current == null) {
            try {
                jdbc.update("insert into mk_decision_kill_switch(tenant_id,namespace_name,switch_sequence,enabled_value,reason_text,directive_signature,directive_json,updated_at) values(?,?,?,?,?,?,?,?)",
                        directive.tenantId().value(), namespace, directive.switchSequence(), directive.enabled(),
                        directive.reason(), directive.signature(), json(directive), format(directive.activatedAt()));
            } catch (DuplicateKeyException race) {
                throw new ConflictException("KILL_SWITCH_CONCURRENT_UPDATE", "kill switch changed concurrently");
            }
        } else {
            jdbc.update("update mk_decision_kill_switch set switch_sequence=?,enabled_value=?,reason_text=?,directive_signature=?,directive_json=?,updated_at=? where tenant_id=? and namespace_name=?",
                    directive.switchSequence(), directive.enabled(), directive.reason(), directive.signature(),
                    json(directive), format(directive.activatedAt()), directive.tenantId().value(), namespace);
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
        jdbc.query("select tenant_id,directive_json from mk_decision_kill_switch where namespace_name=?",
                rs -> {
                    try {
                        KillSwitchDirective directive = mapper.readValue(rs.getString(2), KillSwitchDirective.class);
                        verify(directive);
                        states.compute(rs.getString(1), (ignored, current) -> current == null
                                || directive.switchSequence() > current.sequence()
                                ? new State(directive.switchSequence(), directive.signature(), directive.enabled(),
                                        directive.reason()) : current);
                    } catch (Exception invalid) {
                        LOGGER.error("rejected persisted decision kill-switch for tenant {}; retaining last-known-good",
                                rs.getString(1), invalid);
                    }
                }, namespace);
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
