package com.acme.marketing.platform.error;

import java.util.List;
import java.io.Serial;
import java.io.Serializable;

public final class ValidationException extends DomainException {
    private static final long serialVersionUID = 1L;

    private final transient List<Violation> violations;

    public ValidationException(String code, String message, List<Violation> violations) {
        super(code, message);
        this.violations = List.copyOf(violations);
    }

    public List<Violation> violations() {
        return violations;
    }

    public record Violation(String pointer, String nodeId, String code, String message) implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
    }
}
