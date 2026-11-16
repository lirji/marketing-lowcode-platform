package com.acme.marketing.decision.rule;

import java.util.List;
import java.util.Map;

public interface DecisionTableAdapter {
    Analysis analyze(String namespace, String dmnXml);

    Compilation compile(String namespace, String modelName, String dmnXml);

    Evaluation evaluate(CompiledDecisionTable table, String decisionName, Map<String, Object> context);

    record Analysis(boolean valid, List<String> gaps, List<String> overlaps, List<String> messages) {
        public Analysis {
            gaps = List.copyOf(gaps);
            overlaps = List.copyOf(overlaps);
            messages = List.copyOf(messages);
        }
    }

    record Evaluation(boolean success, Map<String, Object> results, List<String> messages) {
        public Evaluation {
            // DMN 无命中是合法的 null 结果；防修改副本不能把它误报成求值失败。
            results = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(results));
            messages = List.copyOf(messages);
        }
    }

    record Compilation(boolean valid, CompiledDecisionTable table, List<String> messages) {
        public Compilation {
            messages = List.copyOf(messages);
        }
    }

    record CompiledDecisionTable(String namespace, String modelName, byte[] kjar) {
        public CompiledDecisionTable {
            kjar = kjar.clone();
        }

        @Override
        public byte[] kjar() {
            return kjar.clone();
        }
    }
}
