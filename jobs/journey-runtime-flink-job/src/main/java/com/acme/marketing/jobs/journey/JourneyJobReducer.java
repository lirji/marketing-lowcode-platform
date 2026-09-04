package com.acme.marketing.jobs.journey;

import com.acme.marketing.journey.EnrollmentSnapshot;
import com.acme.marketing.journey.JourneyPlan;
import com.acme.marketing.journey.JourneyRuntime;
import com.acme.marketing.journey.JourneySignal;
import com.acme.marketing.journey.JourneyTransition;
import java.time.Instant;
import java.util.Map;

public final class JourneyJobReducer implements java.io.Serializable {
    private static final long serialVersionUID = 1L;
    private static final JourneyRuntime RUNTIME = new JourneyRuntime();
    private final JourneyPlanResolver plans;

    public JourneyJobReducer(JourneyPlanResolver plans) {
        this.plans = plans;
    }

    public Result apply(JourneyJobState current, JourneyJobInput input, long processingTimeMillis) {
        JourneyPlan plan;
        EnrollmentSnapshot snapshot;
        if (current == null) {
            if (input.planReference() == null || input.signal().type() != JourneyJobInput.SignalPayload.Type.START
                    || input.subjectToken().isBlank()) {
                throw new IllegalArgumentException("new enrollment requires subject, signed release and START signal");
            }
            plan = plans.resolve(input.tenantId(), input.planReference(), Instant.ofEpochMilli(processingTimeMillis),
                    true);
            snapshot = EnrollmentSnapshot.start(input.tenantId(), input.enrollmentId(),
                    input.subjectToken(), plan, Instant.ofEpochMilli(input.signal().occurredAtEpochMillis()));
        } else {
            verifyIdentity(current, input);
            plan = plans.resolve(input.tenantId(), current.planReference(),
                    Instant.ofEpochMilli(processingTimeMillis), false);
            snapshot = current.snapshot();
            if (input.planReference() != null) {
                if (!input.planReference().equals(current.planReference())) {
                    throw new IllegalArgumentException("enrollment plan version is pinned and cannot change");
                }
            }
        }

        JourneyTransition transition = RUNTIME.advance(plan, snapshot, input.signal().toDomain());
        Timer timer = resolveTimer(current, transition, processingTimeMillis);
        long expiry = Math.addExact(processingTimeMillis, plan.stateTtl().toMillis());
        JourneyJobInput.PlanReference reference = current == null ? input.planReference() : current.planReference();
        JourneyJobState next = new JourneyJobState(reference, transition.snapshot(), timer.key(), timer.dueAt(), 0,
                expiry);
        return new Result(next, transition);
    }

    public Result fireTimer(JourneyJobState current, long timestamp) {
        if (current == null || current.activeTimerAtEpochMillis() != timestamp) {
            throw new IllegalArgumentException("timer is no longer active");
        }
        JourneySignal.Timer timer = new JourneySignal.Timer(
                "timer:" + current.activeTimerKey() + ':' + timestamp,
                current.activeTimerKey(), Instant.ofEpochMilli(timestamp));
        JourneyPlan plan = plans.resolve(current.snapshot().tenantId(), current.planReference(),
                Instant.ofEpochMilli(timestamp), false);
        JourneyTransition transition = RUNTIME.advance(plan, current.snapshot(), timer);
        Timer nextTimer = timerFromTransition(transition, timestamp);
        long expiry = Math.addExact(timestamp, plan.stateTtl().toMillis());
        return new Result(new JourneyJobState(current.planReference(), transition.snapshot(),
                nextTimer.key(), nextTimer.dueAt(), 0, expiry), transition);
    }

    private static Timer resolveTimer(JourneyJobState current, JourneyTransition transition, long now) {
        if (transition.duplicate() && current != null) {
            return new Timer(current.activeTimerKey(), current.activeTimerAtEpochMillis());
        }
        Timer proposed = timerFromTransition(transition, now);
        if (current != null && !current.activeTimerKey().isBlank()
                && current.snapshot().currentNodeId().equals(transition.snapshot().currentNodeId())
                && current.activeTimerKey().equals(proposed.key())) {
            return new Timer(current.activeTimerKey(), current.activeTimerAtEpochMillis());
        }
        return proposed;
    }

    private static Timer timerFromTransition(JourneyTransition transition, long now) {
        if (transition.timers().isEmpty()) return Timer.none();
        if (transition.timers().size() != 1) {
            throw new IllegalStateException("journey runtime emitted more than one active timer");
        }
        Map.Entry<String, Instant> timer = transition.timers().entrySet().iterator().next();
        return new Timer(timer.getKey(), Math.max(now + 1, timer.getValue().toEpochMilli()));
    }

    private static void verifyIdentity(JourneyJobState state, JourneyJobInput input) {
        EnrollmentSnapshot snapshot = state.snapshot();
        if (!snapshot.tenantId().equals(input.tenantId())
                || !snapshot.enrollmentId().equals(input.enrollmentId())
                || !snapshot.subjectToken().equals(input.subjectToken())) {
            throw new IllegalArgumentException("journey partition identity mismatch");
        }
    }

    private record Timer(String key, long dueAt) {
        static Timer none() {
            return new Timer("", 0);
        }
    }

    public record Result(JourneyJobState state, JourneyTransition transition) {
    }
}
