package com.acme.marketing.control.application;

import com.acme.marketing.journey.EnrollmentSnapshot;
import com.acme.marketing.journey.JourneyCommand;
import com.acme.marketing.journey.JourneyCompiler;
import com.acme.marketing.journey.JourneyRuntime;
import com.acme.marketing.journey.JourneySignal;
import com.acme.marketing.journey.JourneyPlan;
import com.acme.marketing.journey.JourneyNode;
import com.acme.marketing.lowcode.model.Dialect;
import com.acme.marketing.lowcode.model.GraphDefinition;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 复用生产编译器与状态机的纯计算预览；没有Repository、Dispatcher或Outbox依赖。 */
public final class JourneySimulationService {
    private static final int MAX_SIGNALS = 64;
    private static final int MAX_TOTAL_STEPS = 10_000;
    private static final int MAX_STEPS_PER_SIGNAL = 1_000;
    private static final int MAX_ITERATIONS = 100;
    private static final String SIMULATION_ID = "simulation";
    private static final Instant START = Instant.EPOCH;

    /** 按显式虚拟时间推进；不模拟生产处理时间TTL、渠道回执或外部资格。 */
    public Result simulate(GraphDefinition graph, long version, JourneySimulationRequest request) {
        if (graph.dialect() != Dialect.JOURNEY_STATE_MACHINE) throw JourneySimulationErrorCode.DIALECT_REQUIRED.invalid();
        var plan = validatedPlan(graph, version);
        if (plan.maxStepsPerSignal() > MAX_STEPS_PER_SIGNAL || plan.maxIterations() > MAX_ITERATIONS)
            throw JourneySimulationErrorCode.LIMIT_EXCEEDED.invalid();
        int signalLimit = Math.min(MAX_SIGNALS, MAX_TOTAL_STEPS / plan.maxStepsPerSignal());
        var runtime = new JourneyRuntime();
        var snapshot = new EnrollmentSnapshot(SIMULATION_ID, SIMULATION_ID, SIMULATION_ID, plan.journeyId(), version,
                plan.startNodeId(), EnrollmentSnapshot.Status.RUNNING, request.initialVariables(), Set.of(), Map.of(), Map.of(), START);
        List<Step> steps = new ArrayList<>();
        String timerKey = "";
        Instant timerAt = null;
        int eventIndex = 0;
        JourneySignal signal = new JourneySignal.Start("start", START);
        SimulationStimulus kind = SimulationStimulus.INITIAL;
        while (true) {
            String beforeNode = snapshot.currentNodeId();
            var transition = runtime.advance(plan, snapshot, signal);
            snapshot = transition.snapshot();
            steps.add(new Step(steps.size() + 1, kind, signal instanceof JourneySignal.Event event ? event.eventType() : "",
                    seconds(signal.occurredAt()), beforeNode, snapshot.currentNodeId(), snapshot.status(),
                    transition.commands().stream().map(command -> new Command(command.type(), command.nodeId(), command.commandId())).toList()));
            if (snapshot.status() == EnrollmentSnapshot.Status.COMPLETED || snapshot.status() == EnrollmentSnapshot.Status.FAILED) {
                timerAt = null; timerKey = ""; break;
            }
            if (transition.timers().isEmpty()) { timerKey = ""; timerAt = null; }
            else {
                var proposed = transition.timers().entrySet().iterator().next();
                // 与运行Job一致：无关事件停在同一等待节点时，不把已有计时器不断后移。
                if (kind == SimulationStimulus.DUE_TIMER || !beforeNode.equals(snapshot.currentNodeId()) || !timerKey.equals(proposed.getKey())) {
                    timerKey = proposed.getKey();
                    timerAt = proposed.getValue().isAfter(signal.occurredAt()) ? proposed.getValue() : signal.occurredAt().plusMillis(1);
                }
            }
            Instant eventAt = eventIndex < request.events().size() ? START.plusSeconds(request.events().get(eventIndex).offsetSeconds()) : null;
            boolean timerFirst = timerAt != null && !timerAt.isAfter(START.plusSeconds(request.horizonSeconds()))
                    && (eventAt == null || !timerAt.isAfter(eventAt));
            if (!timerFirst && eventAt == null) break;
            if (steps.size() >= signalLimit) return result(plan.journeyId(), version, snapshot, steps, timerAt, StopReason.TRUNCATED, request.events().size() - eventIndex);
            if (timerFirst) {
                signal = new JourneySignal.Timer("timer-" + steps.size(), timerKey, timerAt); kind = SimulationStimulus.DUE_TIMER;
            } else {
                var event = request.events().get(eventIndex++);
                signal = new JourneySignal.Event("event-" + eventIndex, event.eventType(), eventAt, event.attributes()); kind = SimulationStimulus.INPUT_EVENT;
            }
        }
        StopReason reason = snapshot.status() == EnrollmentSnapshot.Status.COMPLETED ? StopReason.COMPLETED
                : snapshot.status() == EnrollmentSnapshot.Status.FAILED ? StopReason.FAILED : StopReason.WAITING;
        return result(plan.journeyId(), version, snapshot, steps, timerAt, reason, request.events().size() - eventIndex);
    }

    private static Result result(String definitionId, long version, EnrollmentSnapshot snapshot, List<Step> steps,
            Instant timerAt, StopReason reason, int remainingEvents) {
        return new Result("JOURNEY_SIMULATION", definitionId, version, reason, snapshot.status(), snapshot.currentNodeId(),
                seconds(snapshot.updatedAt()), remainingEvents, List.copyOf(steps), timerAt == null ? List.of()
                : List.of(new Timer(snapshot.currentNodeId(), seconds(timerAt))), snapshot.status() == EnrollmentSnapshot.Status.FAILED ? snapshot.variables().getOrDefault("failureCode", "") : "");
    }
    private static double seconds(Instant time) { return time.toEpochMilli() / 1000.0; }

    /** 已保存的配置也可能无效；先检查时间算术，避免纯仿真因恶意极值返回500。 */
    private static JourneyPlan validatedPlan(GraphDefinition graph, long version) {
        try {
            var plan = new JourneyCompiler().compile(graph, version);
            for (var node : plan.nodes().values()) {
                if (node.type() == JourneyNode.Type.WAIT_TIMER) {
                    long delay = Long.parseLong(node.config().getOrDefault("delaySeconds", "0"));
                    if (delay < 0) throw JourneySimulationErrorCode.INPUT_INVALID.invalid();
                    START.plusSeconds(JourneySimulationRequest.MAX_HORIZON_SECONDS).plusSeconds(delay).toEpochMilli();
                }
            }
            return plan;
        } catch (IllegalArgumentException | java.time.DateTimeException | ArithmeticException invalid) {
            throw JourneySimulationErrorCode.INPUT_INVALID.invalid();
        }
    }
    public enum SimulationStimulus {
        INITIAL("START"), INPUT_EVENT("EVENT"), DUE_TIMER("TIMER");
        private final String code;
        SimulationStimulus(String code) { this.code = code; }
        @com.fasterxml.jackson.annotation.JsonValue public String code() { return code; }
    }
    public enum StopReason {
        COMPLETED("COMPLETED"), WAITING("WAITING"), FAILED("FAILED"), TRUNCATED("TRUNCATED");
        private final String code;
        StopReason(String code) { this.code = code; }
        @com.fasterxml.jackson.annotation.JsonValue public String code() { return code; }
    }
    public record Command(JourneyCommand.Type type, String nodeId, String commandId) { }
    public record Timer(String nodeId, double dueOffsetSeconds) { }
    public record Step(int sequence, SimulationStimulus signal, String eventType, double offsetSeconds, String fromNodeId,
            String toNodeId, EnrollmentSnapshot.Status status, List<Command> commands) { }
    public record Result(String kind, String definitionId, long version, StopReason stopReason, EnrollmentSnapshot.Status status,
            String currentNodeId, double elapsedSeconds, int remainingEvents, List<Step> steps, List<Timer> pendingTimers, String failureCode) { }
}
