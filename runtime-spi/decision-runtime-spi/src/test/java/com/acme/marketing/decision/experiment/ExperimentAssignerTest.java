package com.acme.marketing.decision.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class ExperimentAssignerTest {
    @Test
    void assignmentDoesNotDriftAcrossRetries() {
        ExperimentAssigner assigner = new ExperimentAssigner();
        ExperimentAssigner.Experiment experiment = new ExperimentAssigner.Experiment("exp-1", "v3", "checkout",
                "a-secret-with-rotation-version", List.of(
                        new ExperimentAssigner.Variant("A", 4_500, false),
                        new ExperimentAssigner.Variant("B", 4_500, false),
                        new ExperimentAssigner.Variant("HOLDOUT", 1_000, true)));

        assertEquals(assigner.assign(experiment, "subject-9"), assigner.assign(experiment, "subject-9"));
    }
}
