package com.acme.marketing.decision.experiment;

import com.acme.marketing.platform.crypto.StableBucket;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class ExperimentAssigner {
    public Assignment assign(Experiment experiment, String randomizationUnit) {
        if (randomizationUnit == null || randomizationUnit.isBlank()) {
            return new Assignment(experiment.experimentId(), experiment.version(), "CONTROL", false, -1);
        }
        int bucket = StableBucket.assign(experiment.salt().getBytes(StandardCharsets.UTF_8),
                experiment.experimentId() + ":" + experiment.version() + ":" + randomizationUnit, 10_000);
        int cursor = 0;
        for (Variant variant : experiment.variants()) {
            cursor += variant.basisPoints();
            if (bucket < cursor) {
                return new Assignment(experiment.experimentId(), experiment.version(), variant.variantId(),
                        variant.holdout(), bucket);
            }
        }
        return new Assignment(experiment.experimentId(), experiment.version(), "CONTROL", true, bucket);
    }

    public record Experiment(String experimentId, String version, String layer, String salt, List<Variant> variants) {
        public Experiment {
            if (experimentId == null || experimentId.isBlank() || version == null || version.isBlank()
                    || layer == null || layer.isBlank() || salt == null || salt.length() < 16) {
                throw new IllegalArgumentException("experiment definition is invalid");
            }
            variants = List.copyOf(variants);
            if (variants.stream().mapToInt(Variant::basisPoints).sum() > 10_000) {
                throw new IllegalArgumentException("variant traffic exceeds 10000 basis points");
            }
        }
    }

    public record Variant(String variantId, int basisPoints, boolean holdout) {
        public Variant {
            if (variantId == null || variantId.isBlank() || basisPoints < 0 || basisPoints > 10_000) {
                throw new IllegalArgumentException("variant is invalid");
            }
        }
    }

    public record Assignment(String experimentId, String version, String variantId, boolean holdout, int bucket) {
    }
}
