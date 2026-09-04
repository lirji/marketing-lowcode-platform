package com.acme.marketing.jobs.support;

public record KafkaJobOutput(String key, String payload, boolean deadLetter) {
    public KafkaJobOutput {
        if (key == null || key.isBlank() || payload == null) {
            throw new IllegalArgumentException("job output key and payload are required");
        }
    }

    public static KafkaJobOutput data(String key, Object payload) {
        return new KafkaJobOutput(key, JsonCodec.write(payload), false);
    }

    public static KafkaJobOutput deadLetter(String key, String input, RuntimeException failure) {
        String source = input == null ? "" : input;
        String safeInput = source.length() <= 4_096 ? source : source.substring(0, 4_096);
        return new KafkaJobOutput(key, JsonCodec.write(new DeadLetter(
                failure.getClass().getSimpleName(), safeMessage(failure), safeInput)), true);
    }

    private static String safeMessage(RuntimeException failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? "message processing failed" : message;
    }

    private record DeadLetter(String errorType, String message, String input) {
    }
}
