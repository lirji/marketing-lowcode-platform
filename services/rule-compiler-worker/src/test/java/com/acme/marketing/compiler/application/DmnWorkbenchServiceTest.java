package com.acme.marketing.compiler.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.acme.marketing.decision.rule.DecisionTableAdapter;
import com.acme.marketing.platform.error.DomainException;
import com.acme.marketing.platform.identity.TenantId;
import com.acme.marketing.platform.identity.TenantScope;
import com.acme.marketing.platform.web.TenantContextHolder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 在有限语法边界内运行真实 KIE；只用阻塞替身验证准入，不用替身证明 DMN 语义。 */
class DmnWorkbenchServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final DmnWorkbenchService service = new DmnWorkbenchService(mapper, new SimpleMeterRegistry());
    private org.mockito.MockedStatic<TenantContextHolder> tenant;

    @BeforeEach void authorize() { tenant = mockStatic(TenantContextHolder.class); tenant.when(TenantContextHolder::requireCurrent).thenReturn(scope()); }
    @AfterEach void cleanup() { tenant.close(); }

    @Test void realAnalysisReportsGapsAndUniqueOverlap() {
        var gap = service.analyze(request("UNIQUE", "number", List.of(rule("< 0", "\"low\"")), null));
        assertFalse(gap.gaps().isEmpty(), gap.toString());
        assertTrue(gap.messages().stream().noneMatch(message -> message.startsWith("ILLEGAL_USE_OF_")), gap.toString());
        var overlap = service.analyze(request("UNIQUE", "number", List.of(rule("< 10", "\"first\""), rule("> 0", "\"second\"")), null));
        assertFalse(overlap.overlaps().isEmpty(), overlap.toString());
        assertFalse(overlap.valid(), overlap.toString());
    }

    @Test void firstPreservesRuleOrderAndUniqueConflictDoesNotPretendToEvaluate() {
        var rules = List.of(rule("< 10", "\"first\""), rule("> 0", "\"second\""));
        var first = service.evaluate(request("FIRST", "number", rules, Map.of("amount", "5")));
        assertTrue(first.success(), first.toString());
        assertEquals("first", first.results().get(DmnWorkbenchInput.DECISION));
        var unique = service.evaluate(request("UNIQUE", "number", rules, Map.of("amount", "5")));
        assertFalse(unique.success());
    }

    @Test void noMatchIsSuccessfulNullAndEqualityIsARealUnaryTest() {
        var input = request("UNIQUE", "number", List.of(rule("= 1", "\"one\"")), Map.of("amount", "2"));
        var result = service.evaluate(input);
        assertTrue(result.success(), result.toString());
        assertTrue(result.results().containsKey(DmnWorkbenchInput.DECISION));
        assertNull(result.results().get(DmnWorkbenchInput.DECISION));
        assertThrows(UnsupportedOperationException.class, () -> result.results().put("changed", "no"));
        assertEquals("one", service.evaluate(request("UNIQUE", "number", List.of(rule("= 1", "\"one\"")), Map.of("amount", "1")))
                .results().get(DmnWorkbenchInput.DECISION));
    }

    @Test void exactNumbersAndMultipleOutputsPreserveAllDigitsAndTypedScalars() {
        Map<String, Object> table = Map.of("hitPolicy", "UNIQUE", "inputs", List.of(column("amount", "number")),
                "outputs", List.of(column("total", "number"), column("label", "string"), column("allowed", "boolean")),
                "rules", List.of(Map.of("inputs", List.of("-"), "outputs", List.of("999999999999.123456", "\"a<&b\"", "true"))));
        var result = service.evaluate(mapper.valueToTree(Map.of("table", table, "context", Map.of("amount", "999999999999999999"))));
        assertTrue(result.success(), result.toString());
        assertEquals("999999999999999999", result.results().get("amount"));
        assertEquals(Map.of("total", "999999999999.123456", "label", "a<&b", "allowed", true), result.results().get(DmnWorkbenchInput.DECISION));
    }

    @Test void stringJsonEscapesAndBooleanTestsUseTypedInputs() {
        String text = "a<&\"b";
        var string = service.evaluate(request("FIRST", "string", List.of(rule(mapper.writeValueAsString(text), "\"match\"")), Map.of("amount", text)));
        assertTrue(string.success(), string.toString());
        assertEquals("match", string.results().get(DmnWorkbenchInput.DECISION));
        var bool = service.evaluate(request("FIRST", "boolean", List.of(rule("true", "\"yes\""), rule("false", "\"no\"")), Map.of("amount", "false")));
        assertTrue(bool.success(), bool.toString());
        assertEquals("no", bool.results().get(DmnWorkbenchInput.DECISION));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1e3", "1+1", "[1..3]", "1,2", "not(1)", "date(\"2026-01-01\")", "1000000000000000000", "0.1234567", "< 1 or true"})
    void rejectsExpressionsAndUnboundedNumbersBeforeKie(String cell) {
        var failure = assertThrows(DomainException.class, () -> service.analyze(request("UNIQUE", "number", List.of(rule(cell, "\"yes\"")), null)));
        assertTrue(failure.code().startsWith("DMN_"));
    }

    @Test void rejectsStructureLimitsDuplicateReservedNamesAndContextCoercion() {
        var valid = request("UNIQUE", "number", List.of(rule("-", "\"yes\"")), null);
        for (String field : List.of("inputs", "outputs")) {
            var changed = valid.deepCopy();
            ((tools.jackson.databind.node.ObjectNode) changed.get("table")).set(field, mapper.valueToTree(java.util.Collections.nCopies(5, column("x", "number"))));
            assertCode("DMN_TABLE_LIMIT", changed, false);
        }
        var rules = valid.deepCopy();
        ((tools.jackson.databind.node.ObjectNode) rules.get("table")).set("rules", mapper.valueToTree(java.util.Collections.nCopies(33, rule("-", "\"x\""))));
        assertCode("DMN_TABLE_LIMIT", rules, false);
        for (String name : List.of("amount", "DmnWorkbenchModel", "DmnWorkbenchDecision", "for", "x-y", "x".repeat(33))) {
            var changed = valid.deepCopy();
            ((tools.jackson.databind.node.ObjectNode) changed.get("table").get("outputs").get(0)).put("name", name);
            assertCode("DMN_NAME_INVALID", changed, false);
        }
        var badContext = valid.deepCopy();
        ((tools.jackson.databind.node.ObjectNode) badContext).set("context", mapper.valueToTree(Map.of("amount", 12)));
        assertCode("DMN_CONTEXT_INVALID", badContext, true);
        ((tools.jackson.databind.node.ObjectNode) badContext).set("context", mapper.valueToTree(Map.of("other", "12")));
        assertCode("DMN_CONTEXT_INVALID", badContext, true);
        assertCode("DMN_CELL_INVALID", request("FIRST", "string", List.of(rule("\"ok\" or true", "\"x\"")), null), false);
        assertCode("DMN_CELL_INVALID", request("FIRST", "string", List.of(rule("-", "\"" + "x".repeat(255) + "\"")), null), false);
    }

    @Test void oneAdmissionRejectsConcurrentCallAndReleasesAfterFailure() throws Exception {
        var adapter = mock(DecisionTableAdapter.class);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        when(adapter.analyze(anyString(), anyString())).thenAnswer(call -> {
            entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS));
            throw new IllegalStateException("injected failure");
        });
        var blocked = new DmnWorkbenchService(mapper, new SimpleMeterRegistry(), adapter);
        var input = request("UNIQUE", "number", List.of(rule("-", "\"ok\"")), null);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var running = pool.submit(() -> {
                try (var context = mockStatic(TenantContextHolder.class)) {
                    context.when(TenantContextHolder::requireCurrent).thenReturn(scope());
                    return blocked.analyze(input);
                }
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals("DMN_WORKBENCH_BUSY", assertThrows(DomainException.class, () -> service.analyze(input)).code());
            release.countDown(); assertThrows(java.util.concurrent.ExecutionException.class, () -> running.get(5, TimeUnit.SECONDS));
        } finally { release.countDown(); }
        assertTrue(service.analyze(input).valid());
    }

    private void assertCode(String code, JsonNode request, boolean evaluate) {
        assertEquals(code, assertThrows(DomainException.class, () -> { if (evaluate) service.evaluate(request); else service.analyze(request); }).code());
    }
    private JsonNode request(String policy, String type, List<Map<String, Object>> rules, Map<String, String> context) {
        var table = Map.of("hitPolicy", policy, "inputs", List.of(column("amount", type)), "outputs", List.of(column("answer", "string")), "rules", rules);
        return mapper.valueToTree(context == null ? Map.of("table", table) : Map.of("table", table, "context", context));
    }
    private static Map<String, Object> rule(String input, String output) { return Map.of("inputs", List.of(input), "outputs", List.of(output)); }
    private static Map<String, String> column(String name, String type) { return Map.of("name", name, "type", type); }
    private static TenantScope scope() { return new TenantScope(new TenantId("dmn-test"), Set.of(), Set.of(), "tester", Set.of("*")); }
}
