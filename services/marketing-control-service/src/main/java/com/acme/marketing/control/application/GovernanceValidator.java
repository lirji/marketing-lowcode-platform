package com.acme.marketing.control.application;

import com.acme.marketing.lowcode.model.GraphDefinition;
import com.acme.marketing.lowcode.validation.ValidationIssue;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public final class GovernanceValidator {
    public List<ValidationIssue> validate(GraphDefinition graph) {
        List<ValidationIssue> issues = new ArrayList<>();
        if (Boolean.parseBoolean(graph.annotations().getOrDefault("personalized", "false"))) {
            required(graph, issues, "personalizationOptOut", "PERSONALIZATION_OPT_OUT_REQUIRED");
            required(graph, issues, "personalizationExplanation", "PERSONALIZATION_EXPLANATION_REQUIRED");
            required(graph, issues, "genericRoute", "GENERIC_ROUTE_REQUIRED");
        }
        if (Boolean.parseBoolean(graph.annotations().getOrDefault("merchantFunding", "false"))) {
            required(graph, issues, "merchantConsentId", "MERCHANT_CONSENT_REQUIRED");
        }
        if (Boolean.parseBoolean(graph.annotations().getOrDefault("differentialPrice", "false"))
                && !Boolean.parseBoolean(graph.annotations().getOrDefault("fairnessReviewed", "false"))) {
            issues.add(error("FAIRNESS_REVIEW_REQUIRED", "/annotations/fairnessReviewed",
                    "differential pricing requires accountable fairness review"));
        }
        return List.copyOf(issues);
    }

    private static void required(GraphDefinition graph, List<ValidationIssue> issues, String key, String code) {
        if (graph.annotations().getOrDefault(key, "").isBlank()) {
            issues.add(error(code, "/annotations/" + key, key + " is required"));
        }
    }

    private static ValidationIssue error(String code, String pointer, String message) {
        return new ValidationIssue(ValidationIssue.Severity.ERROR, code, pointer, null, message);
    }
}
