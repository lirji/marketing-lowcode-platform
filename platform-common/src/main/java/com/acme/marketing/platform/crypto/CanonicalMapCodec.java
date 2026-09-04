package com.acme.marketing.platform.crypto;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

public final class CanonicalMapCodec {
    private CanonicalMapCodec() {
    }

    public static byte[] encode(Map<String, String> values) {
        StringBuilder canonical = new StringBuilder();
        new TreeMap<>(values).forEach((key, value) -> {
            requireSafe(key, "key");
            String actual = value == null ? "" : value;
            canonical.append(key.length()).append(':').append(key)
                    .append(actual.getBytes(StandardCharsets.UTF_8).length).append(':').append(actual);
        });
        return canonical.toString().getBytes(StandardCharsets.UTF_8);
    }

    public static Map<String, String> decode(byte[] bytes) {
        String encoded = new String(bytes, StandardCharsets.UTF_8);
        Map<String, String> result = new LinkedHashMap<>();
        int cursor = 0;
        while (cursor < encoded.length()) {
            int keySeparator = encoded.indexOf(':', cursor);
            int keyLength = parseLength(encoded, cursor, keySeparator);
            int keyStart = keySeparator + 1;
            int keyEnd = keyStart + keyLength;
            if (keyEnd > encoded.length()) {
                throw new IllegalArgumentException("truncated canonical key");
            }
            String key = encoded.substring(keyStart, keyEnd);
            int valueSeparator = encoded.indexOf(':', keyEnd);
            int valueByteLength = parseLength(encoded, keyEnd, valueSeparator);
            int valueStart = valueSeparator + 1;
            int valueEnd = utf8End(encoded, valueStart, valueByteLength);
            if (result.putIfAbsent(key, encoded.substring(valueStart, valueEnd)) != null) {
                throw new IllegalArgumentException("duplicate canonical key");
            }
            cursor = valueEnd;
        }
        return Map.copyOf(result);
    }

    private static int parseLength(String value, int start, int separator) {
        if (separator <= start) {
            throw new IllegalArgumentException("invalid canonical length");
        }
        try {
            int parsed = Integer.parseInt(value.substring(start, separator));
            if (parsed < 0 || parsed > 1_000_000) {
                throw new IllegalArgumentException("canonical field is too large");
            }
            return parsed;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("invalid canonical length", invalid);
        }
    }

    private static int utf8End(String value, int start, int byteLength) {
        int end = start;
        int count = 0;
        while (end < value.length() && count < byteLength) {
            int codePoint = value.codePointAt(end);
            count += new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8).length;
            end += Character.charCount(codePoint);
        }
        if (count != byteLength) {
            throw new IllegalArgumentException("invalid UTF-8 field length");
        }
        return end;
    }

    private static void requireSafe(String value, String name) {
        if (value == null || !value.matches("[a-zA-Z][a-zA-Z0-9_.:-]{0,255}")) {
            throw new IllegalArgumentException(name + " is invalid");
        }
    }
}
