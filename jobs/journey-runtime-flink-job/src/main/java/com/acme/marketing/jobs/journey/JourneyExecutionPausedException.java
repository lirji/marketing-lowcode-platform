package com.acme.marketing.jobs.journey;

final class JourneyExecutionPausedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    JourneyExecutionPausedException(String message) { super(message); }
}
