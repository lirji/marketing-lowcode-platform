package com.acme.marketing.journey;

import com.acme.marketing.platform.crypto.Digests;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class JourneyRuntime {
    public JourneyTransition advance(JourneyPlan plan, EnrollmentSnapshot input, JourneySignal signal) {
        verifyPinnedVersion(plan, input);
        if (input.processedSignalIds().contains(signal.signalId())) {
            return new JourneyTransition(input, List.of(), Map.of(), true);
        }
        if (input.status() == EnrollmentSnapshot.Status.COMPLETED
                || input.status() == EnrollmentSnapshot.Status.FAILED) {
            return new JourneyTransition(input, List.of(), Map.of(), false);
        }
        Set<String> processed = new HashSet<>(input.processedSignalIds());
        processed.add(signal.signalId());
        Map<String, String> variables = new HashMap<>(input.variables());
        if (signal instanceof JourneySignal.Event event) {
            variables.putAll(event.attributes());
            variables.put("eventType", event.eventType());
        }
        Map<String, Integer> iterations = new HashMap<>(input.iterations());
        Map<String, Integer> nodeExecutions = new HashMap<>(input.nodeExecutions());
        List<JourneyCommand> commands = new ArrayList<>();
        Map<String, Instant> timers = new HashMap<>();
        String nodeId = input.currentNodeId();
        EnrollmentSnapshot.Status status = EnrollmentSnapshot.Status.RUNNING;

        for (int step = 0; step < plan.maxStepsPerSignal(); step++) {
            JourneyNode node = requiredNode(plan, nodeId);
            switch (node.type()) {
                case TRIGGER -> nodeId = node.route("next");
                case CONDITION -> nodeId = node.route(matches(node, variables) ? "true" : "false");
                case WAIT_EVENT -> {
                    if (signal instanceof JourneySignal.Event event
                            && node.config().getOrDefault("eventType", "*").matches("\\*|" + java.util.regex.Pattern.quote(event.eventType()))) {
                        nodeId = node.route("matched");
                    } else {
                        status = EnrollmentSnapshot.Status.WAITING;
                        return transition(input, nodeId, status, variables, processed, iterations,
                                nodeExecutions, signal.occurredAt(), commands, timers, false);
                    }
                }
                case WAIT_TIMER -> {
                    String timerKey = input.enrollmentId() + ':' + node.id();
                    if (signal instanceof JourneySignal.Timer timer && timerKey.equals(timer.timerKey())) {
                        nodeId = node.route("elapsed");
                    } else {
                        long seconds = Long.parseLong(node.config().getOrDefault("delaySeconds", "0"));
                        timers.put(timerKey, signal.occurredAt().plusSeconds(seconds));
                        status = EnrollmentSnapshot.Status.WAITING;
                        return transition(input, nodeId, status, variables, processed, iterations,
                                nodeExecutions, signal.occurredAt(), commands, timers, false);
                    }
                }
                case SEND, GRANT, WEBHOOK -> {
                    JourneyCommand.Type commandType = JourneyCommand.Type.valueOf(node.type().name());
                    int execution = nodeExecutions.getOrDefault(node.id(), 0) + 1;
                    nodeExecutions.put(node.id(), execution);
                    String commandId = Digests.sha256Hex(input.tenantId() + ':' + input.enrollmentId() + ':'
                            + plan.version() + ':' + node.id() + ':' + execution);
                    commands.add(new JourneyCommand(commandId, commandType, node.id(), node.config()));
                    nodeId = node.route("next");
                }
                case REPEAT -> {
                    int next = iterations.getOrDefault(node.id(), 0) + 1;
                    iterations.put(node.id(), next);
                    nodeId = node.route(next <= plan.maxIterations() ? "repeat" : "exit");
                }
                case END -> {
                    status = EnrollmentSnapshot.Status.COMPLETED;
                    return transition(input, nodeId, status, variables, processed, iterations,
                            nodeExecutions, signal.occurredAt(), commands, timers, false);
                }
            }
        }
        status = EnrollmentSnapshot.Status.FAILED;
        variables.put("failureCode", "MAX_STEPS_EXCEEDED");
        return transition(input, nodeId, status, variables, processed, iterations,
                nodeExecutions, signal.occurredAt(), commands, timers, false);
    }

    private static boolean matches(JourneyNode node, Map<String, String> variables) {
        String field = node.config().get("field");
        String expected = node.config().get("equals");
        return expected != null && expected.equals(variables.get(field));
    }

    private static JourneyTransition transition(
            EnrollmentSnapshot original,
            String nodeId,
            EnrollmentSnapshot.Status status,
            Map<String, String> variables,
            Set<String> processed,
            Map<String, Integer> iterations,
            Map<String, Integer> nodeExecutions,
            Instant now,
            List<JourneyCommand> commands,
            Map<String, Instant> timers,
            boolean duplicate) {
        EnrollmentSnapshot next = new EnrollmentSnapshot(original.tenantId(), original.enrollmentId(),
                original.subjectToken(), original.journeyId(), original.journeyVersion(), nodeId, status,
                variables, processed, iterations, nodeExecutions, now);
        return new JourneyTransition(next, commands, timers, duplicate);
    }

    private static JourneyNode requiredNode(JourneyPlan plan, String nodeId) {
        JourneyNode node = plan.nodes().get(nodeId);
        if (node == null) {
            throw new IllegalStateException("journey node not found: " + nodeId);
        }
        return node;
    }

    private static void verifyPinnedVersion(JourneyPlan plan, EnrollmentSnapshot snapshot) {
        if (!plan.journeyId().equals(snapshot.journeyId()) || plan.version() != snapshot.journeyVersion()) {
            throw new IllegalArgumentException("enrollment is pinned to another journey version");
        }
    }
}
