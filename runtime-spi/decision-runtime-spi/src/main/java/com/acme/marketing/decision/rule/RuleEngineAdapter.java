package com.acme.marketing.decision.rule;

import java.time.Duration;
import java.util.List;
import java.util.Map;

public interface RuleEngineAdapter {
    CompilationResult compile(String namespace, String source, Duration budget);

    EvaluationResult evaluate(CompiledRules rules, Map<String, Object> facts, int fireLimit, Duration budget);

    record CompiledRules(String namespace, byte[] executableModel, String engineVersion) {
        public CompiledRules {
            executableModel = executableModel.clone();
        }

        @Override
        public byte[] executableModel() {
            return executableModel.clone();
        }
    }

    record CompilationResult(boolean valid, CompiledRules rules, List<String> messages) {
        public CompilationResult {
            messages = List.copyOf(messages);
        }
    }

    record EvaluationResult(boolean matched, List<String> resultCodes, List<String> messages) {
        public EvaluationResult {
            resultCodes = List.copyOf(resultCodes);
            messages = List.copyOf(messages);
        }
    }
}
