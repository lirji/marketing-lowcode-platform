package com.acme.marketing.jobs.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import org.junit.jupiter.api.Test;

class FlinkKafkaJobRunnerTest {
    @Test
    void outputTopicSelectorSurvivesJobGraphSerialization() throws Exception {
        var selector = new FlinkKafkaJobRunner.OutputTopicSelector("mk.output.v1");

        byte[] serialized;
        try (var bytes = new ByteArrayOutputStream(); var output = new ObjectOutputStream(bytes)) {
            output.writeObject(selector);
            serialized = bytes.toByteArray();
        }

        FlinkKafkaJobRunner.OutputTopicSelector restored;
        try (var input = new ObjectInputStream(new ByteArrayInputStream(serialized))) {
            restored = (FlinkKafkaJobRunner.OutputTopicSelector) input.readObject();
        }

        assertNotNull(restored);
        assertEquals("mk.output.v1", restored.apply(new KafkaJobOutput("key", "{}", false)));
        assertEquals("mk.output.v1.dlq", restored.apply(new KafkaJobOutput("key", "{}", true)));
    }
}
