package com.acme.marketing.compiler.application;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A deliberately small source language boundary for administrator-supplied DRL and DMN. */
final class UntrustedRuleSourcePolicy {
    private static final Pattern CONSEQUENCE = Pattern.compile("(?is)\\bthen\\b(.*?)\\bend\\b");
    private static final Pattern ALLOWED_CONSEQUENCE = Pattern.compile(
            "(?s)(?:\\s*resultCodes\\.add\\(\"[A-Z][A-Z0-9_]{0,63}\"\\);\\s*)+");
    private static final Pattern PACKAGE = Pattern.compile("package\\s+[a-zA-Z][a-zA-Z0-9_.]*\\s*;?");
    private static final String RULE_FACTS_IMPORT =
            "import com.acme.marketing.decision.rule.RuleFacts";
    private static final String RESULTS_GLOBAL = "global java.util.List resultCodes";
    private static final int MAX_RULES = 500;

    void validateDrl(String source) {
        String sanitized = source.replace(RULE_FACTS_IMPORT, "").replace(RESULTS_GLOBAL, "");
        String lowered = sanitized.toLowerCase(Locale.ROOT);
        String[] forbidden = {"java.", "javax.", "jakarta.", "org.", "com.", "system.", "runtime",
                "processbuilder", "class.forname", " getclass(", " new ", "function ", "declare ",
                "query ", " eval(", "accumulate(", "collect(", " from ", "entry-point",
                " insert(", " update(", " delete(", " retract(", " modify("};
        for (String token : forbidden) {
            if (lowered.contains(token)) {
                throw new IllegalArgumentException("DRL_SOURCE_POLICY_FORBIDDEN_TOKEN");
            }
        }
        for (String line : source.lines().map(String::trim).toList()) {
            if (line.startsWith("import ") && !line.replace(";", "").equals(RULE_FACTS_IMPORT)) {
                throw new IllegalArgumentException("DRL_SOURCE_POLICY_IMPORT_NOT_ALLOWED");
            }
            if (line.startsWith("global ") && !line.replace(";", "").equals(RESULTS_GLOBAL)) {
                throw new IllegalArgumentException("DRL_SOURCE_POLICY_GLOBAL_NOT_ALLOWED");
            }
            if (line.startsWith("package ") && !PACKAGE.matcher(line).matches()) {
                throw new IllegalArgumentException("DRL_SOURCE_POLICY_PACKAGE_INVALID");
            }
        }

        Matcher matcher = CONSEQUENCE.matcher(source);
        int rules = 0;
        while (matcher.find()) {
            rules++;
            if (!ALLOWED_CONSEQUENCE.matcher(matcher.group(1)).matches()) {
                throw new IllegalArgumentException("DRL_SOURCE_POLICY_RHS_NOT_ALLOWED");
            }
        }
        long declaredRules = Pattern.compile("(?im)^\\s*rule\\s+").matcher(source).results().count();
        if (rules == 0 || rules != declaredRules || rules > MAX_RULES) {
            throw new IllegalArgumentException("DRL_SOURCE_POLICY_RULE_BOUND_INVALID");
        }
    }

    void validateDmn(String source) {
        String lowered = source.toLowerCase(Locale.ROOT);
        String[] forbidden = {"<!doctype", "<!entity", "<import", "<extensionelements",
                "xsi:schemalocation", "java:", "javascript:", "groovy:", "mvel:",
                "href=\"http", "href='http", "href=\"file:", "href='file:"};
        for (String token : forbidden) {
            if (lowered.contains(token)) {
                throw new IllegalArgumentException("DMN_SOURCE_POLICY_EXTERNAL_OR_EXECUTABLE_REFERENCE");
            }
        }
    }
}
