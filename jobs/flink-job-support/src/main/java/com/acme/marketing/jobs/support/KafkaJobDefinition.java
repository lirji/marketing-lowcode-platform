package com.acme.marketing.jobs.support;

public record KafkaJobDefinition(
        String jobName,
        String inputTopic,
        String outputTopic,
        String consumerGroup,
        String transactionalPrefix) {
    public KafkaJobDefinition {
        require(jobName, "job name");
        require(inputTopic, "input topic");
        require(outputTopic, "output topic");
        require(consumerGroup, "consumer group");
        require(transactionalPrefix, "transactional prefix");
    }

    private static void require(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
    }
}
