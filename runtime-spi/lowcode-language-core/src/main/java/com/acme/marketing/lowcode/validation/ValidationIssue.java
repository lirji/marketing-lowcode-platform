package com.acme.marketing.lowcode.validation;

public record ValidationIssue(Severity severity, String code, String pointer, String nodeId, String message) {
    public enum Severity {
        ERROR,
        WARNING
    }
}
