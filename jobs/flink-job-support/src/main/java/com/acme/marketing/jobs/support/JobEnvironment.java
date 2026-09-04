package com.acme.marketing.jobs.support;

public final class JobEnvironment {
    private JobEnvironment() {
    }

    public static String value(String name, String fallback) {
        String system = System.getProperty(name);
        if (system != null && !system.isBlank()) return system;
        String environment = System.getenv(name);
        return environment == null || environment.isBlank() ? fallback : environment;
    }
}
