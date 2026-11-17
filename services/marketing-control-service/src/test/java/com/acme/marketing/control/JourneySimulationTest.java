package com.acme.marketing.control;

import static org.junit.jupiter.api.Assertions.*;

import com.acme.marketing.control.application.GraphSimulationService;
import com.acme.marketing.control.application.JourneySimulationRequest;
import com.acme.marketing.control.application.JourneySimulationService;
import com.acme.marketing.journey.JourneyCommand;
import com.acme.marketing.lowcode.model.Dialect;
import com.acme.marketing.lowcode.model.GraphDefinition;
import com.acme.marketing.lowcode.model.GraphEdge;
import com.acme.marketing.lowcode.model.GraphNode;
import com.acme.marketing.platform.error.DomainException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class JourneySimulationTest {
    private final JourneySimulationService service = new JourneySimulationService();

    @Test void evaluatesBothBranchesWithStableSummariesAndNoPriceFields() {
        var graph = graph(List.of(node("s", "trigger"), node("c", "condition", Map.of("field", "vip", "equals", "yes")),
                node("send", "send"), node("grant", "grant"), node("end", "end")),
                List.of(edge("s", "next", "c"), edge("c", "true", "send"), edge("c", "false", "grant"), edge("send", "next", "end"), edge("grant", "next", "end")), Map.of());
        var yes = service.simulate(graph, 3, request(Map.of("vip", "yes"), List.of(), 0));
        var no = service.simulate(graph, 3, request(Map.of(), List.of(), 0));
        assertEquals(JourneyCommand.Type.SEND, yes.steps().getFirst().commands().getFirst().type());
        assertEquals(JourneyCommand.Type.GRANT, no.steps().getFirst().commands().getFirst().type());
        assertEquals(JourneySimulationService.StopReason.COMPLETED, yes.stopReason());
        assertEquals(yes, service.simulate(graph, 3, request(Map.of("vip", "yes"), List.of(), 0)));
        var json = new JsonMapper().valueToTree(yes);
        assertFalse(json.has("payableMinor")); assertFalse(json.toString().contains("payload"));
    }

    @Test void waitingEventIgnoresUnmatchedAndUsesMatchingAttributes() {
        var graph = graph(List.of(node("s", "trigger"), node("wait", "wait-event", Map.of("eventType", "PAID")),
                node("c", "condition", Map.of("field", "paid", "equals", "yes")), node("send", "send"), node("end", "end")),
                List.of(edge("s", "next", "wait"), edge("wait", "matched", "c"), edge("c", "true", "send"), edge("c", "false", "end"), edge("send", "next", "end")), Map.of());
        var waiting = service.simulate(graph, 1, request(Map.of(), List.of(event(2, "OTHER", Map.of())), 10));
        assertEquals(JourneySimulationService.StopReason.WAITING, waiting.stopReason()); assertEquals(2, waiting.steps().size());
        var done = service.simulate(graph, 1, request(Map.of(), List.of(event(2, "OTHER", Map.of()), event(5, "PAID", Map.of("paid", "yes")), event(6, "IGNORED", Map.of())), 10));
        assertEquals(JourneySimulationService.StopReason.COMPLETED, done.stopReason()); assertEquals(5, done.elapsedSeconds());
        assertEquals(1, done.steps().getLast().commands().size()); assertEquals(1, done.remainingEvents());
    }

    @Test void timerPreservesDeadlineOnUnrelatedEventsAndWinsEqualTimeTie() {
        var graph = graph(List.of(node("s", "trigger"), node("wait", "wait-timer", Map.of("delaySeconds", "10")), node("end", "end")),
                List.of(edge("s", "next", "wait"), edge("wait", "elapsed", "end")), Map.of());
        var waiting = service.simulate(graph, 1, request(Map.of(), List.of(), 9));
        assertEquals(10, waiting.pendingTimers().getFirst().dueOffsetSeconds());
        var done = service.simulate(graph, 1, request(Map.of(), List.of(event(5, "OTHER", Map.of()), event(10, "TIE", Map.of())), 10));
        assertEquals(10, done.elapsedSeconds()); assertEquals(1, done.remainingEvents());
        assertEquals(JourneySimulationService.SimulationStimulus.DUE_TIMER, done.steps().getLast().signal());
    }

    @Test void repeatUsesProductionIterationAndStepBudgets() {
        var nodes = List.of(node("s", "trigger"), node("send", "send"), node("repeat", "repeat"), node("end", "end"));
        var edges = List.of(edge("s", "next", "send"), edge("send", "next", "repeat"), edge("repeat", "repeat", "send"), edge("repeat", "exit", "end"));
        var repeated = service.simulate(graph(nodes, edges, Map.of("maxIterations", "2")), 1, request(Map.of(), List.of(), 0));
        assertEquals(3, repeated.steps().getFirst().commands().stream().map(JourneySimulationService.Command::commandId).distinct().count());
        var failed = service.simulate(graph(nodes, edges, Map.of("maxStepsPerSignal", "3")), 1, request(Map.of(), List.of(), 0));
        assertEquals(JourneySimulationService.StopReason.FAILED, failed.stopReason()); assertEquals("MAX_STEPS_EXCEEDED", failed.failureCode());
        assertThrows(DomainException.class, () -> service.simulate(graph(nodes, edges, Map.of("maxStepsPerSignal", "1001")), 1, request(Map.of(), List.of(), 0)));
    }

    @Test void zeroDelayRepeatedTimersAreBoundedAndDoNotPretendCompletion() {
        var graph = graph(List.of(node("s", "trigger"), node("wait", "wait-timer", Map.of("delaySeconds", "0")), node("wait2", "wait-timer", Map.of("delaySeconds", "0")), node("repeat", "repeat"), node("end", "end")),
                List.of(edge("s", "next", "wait"), edge("wait", "elapsed", "wait2"), edge("wait2", "elapsed", "repeat"), edge("repeat", "repeat", "wait"), edge("repeat", "exit", "end")), Map.of("maxIterations", "100"));
        var result = service.simulate(graph, 1, request(Map.of(), List.of(), 1));
        assertEquals(JourneySimulationService.StopReason.TRUNCATED, result.stopReason()); assertEquals(64, result.steps().size());
        assertTrue(result.pendingTimers().getFirst().dueOffsetSeconds() > result.elapsedSeconds());
    }

    @Test void savedMalformedOrOverflowingTimerConfigurationIsRejected() {
        for (String delay : List.of("-1", "not-a-number", Long.toString(Long.MAX_VALUE))) {
            var graph = graph(List.of(node("s", "trigger"), node("wait", "wait-timer", Map.of("delaySeconds", delay)), node("end", "end")),
                    List.of(edge("s", "next", "wait"), edge("wait", "elapsed", "end")), Map.of());
            assertEquals("JOURNEY_SIMULATION_INPUT_INVALID", assertThrows(DomainException.class,
                    () -> service.simulate(graph, 1, request(Map.of(), List.of(), 10))).code());
        }
    }

    @Test void inputShapeBoundsOrderAndLegacyFakePriceAreRejected() {
        var mapper = new JsonMapper();
        for (String json : List.of("{}", "{\"initialVariables\":{\"vip\":true},\"events\":[],\"horizonSeconds\":1}",
                "{\"initialVariables\":{},\"events\":[],\"horizonSeconds\":1.5}")) {
            assertThrows(DomainException.class, () -> JourneySimulationRequest.read(mapper.readTree(json)));
        }
        assertThrows(DomainException.class, () -> request(Map.of(), List.of(event(5, "A", Map.of()), event(4, "B", Map.of())), 10));
        assertThrows(DomainException.class, () -> request(Map.of(), List.of(event(11, "A", Map.of())), 10));
        assertThrows(DomainException.class, () -> request(Map.of(" ", "x"), List.of(), 10));
        assertThrows(DomainException.class, () -> request(Map.of(), java.util.Collections.nCopies(33, event(0, "A", Map.of())), 1));
        assertThrows(DomainException.class, () -> request(Map.of(), List.of(), 604801));
        var graph = graph(List.of(node("s", "trigger"), node("end", "end")), List.of(edge("s", "next", "end")), Map.of());
        assertEquals("JOURNEY_SIMULATION_ENDPOINT_REQUIRED", assertThrows(DomainException.class,
                () -> new GraphSimulationService().simulate(graph, Map.of())).code());
    }

    static GraphDefinition graph(List<GraphNode> nodes, List<GraphEdge> edges, Map<String, String> variables) { return new GraphDefinition("journey-test", Dialect.JOURNEY_STATE_MACHINE, "1.0.0", nodes, edges, variables, Map.of()); }
    static GraphNode node(String id, String type) { return node(id, type, Map.of()); }
    static GraphNode node(String id, String type, Map<String, String> config) { return new GraphNode(id, "journey." + type, "1.0.0", config); }
    static GraphEdge edge(String from, String port, String to) { return new GraphEdge(from + '-' + port, from, port, to, "in"); }
    static JourneySimulationRequest request(Map<String, String> initial, List<JourneySimulationRequest.Event> events, long horizon) { return new JourneySimulationRequest(initial, events, horizon); }
    static JourneySimulationRequest.Event event(long offset, String type, Map<String, String> attributes) { return new JourneySimulationRequest.Event(offset, type, attributes); }
}
