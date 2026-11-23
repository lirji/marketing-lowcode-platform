package com.acme.marketing.jobs.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;

public final class JsonCodec {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private static final ObjectWriter DEFAULT_WRITER = MAPPER.writer();
    private static final ObjectWriter ISO_DATE_WRITER = DEFAULT_WRITER.without(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

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
        return write(value, DEFAULT_WRITER);
    }

    /** 按事件 schema 的 date-time 字段编码，保留原作业状态的旧编码。 */
    public static String writeWithIsoDates(Object value) {
        return write(value, ISO_DATE_WRITER);
    }

    private static String write(Object value, ObjectWriter writer) {
        try {
            return writer.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("job message cannot be serialized", failure);
        }
    }
}
