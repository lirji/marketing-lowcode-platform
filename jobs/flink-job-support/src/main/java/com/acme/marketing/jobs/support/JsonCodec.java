package com.acme.marketing.jobs.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class JsonCodec {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private JsonCodec() {
    }

    public static <T> T read(String value, Class<T> type) {
        try {
            return MAPPER.readValue(value, type);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("invalid job message", failure);
        }
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("job message cannot be serialized", failure);
        }
    }
}
