package com.acme.marketing.platform.archive;

import com.acme.marketing.platform.crypto.Digests;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** 营销服务共享归档字节格式和预算，业务字段和授权由来源服务定义。 */
public final class AuditArchiveDocument {
    public static final int FORMAT = 1;
    public static final int MAX_ROWS = 200;
    private static final int MAX_COMPRESSED = 512 * 1024;
    private static final int MAX_UNCOMPRESSED = 2 * 1024 * 1024;

    private AuditArchiveDocument() {}

    /** 首行为包含来源和授权范围的 JSON 清单，之后每行保存一项来源证据。 */
    public static byte[] pack(String header, List<String> rows) {
        requireRowCount(rows.size());
        var document = new StringBuilder();
        append(document, header);
        for (String row : rows) append(document, row);
        byte[] raw = document.toString().getBytes(StandardCharsets.UTF_8);
        if (raw.length > MAX_UNCOMPRESSED) throw invalid();
        try {
            var output = new ByteArrayOutputStream();
            try (var gzip = new GZIPOutputStream(output)) {
                gzip.write(raw);
            }
            if (output.size() > MAX_COMPRESSED) throw invalid();
            return output.toByteArray();
        } catch (IOException failure) {
            throw invalid();
        }
    }

    /** 在反序列化来源数据之前按摘要、大小和行数拒绝不一致对象。 */
    public static Lines unpack(byte[] encoded, String sha256, int expectedBytes, int expectedRows) {
        requireRowCount(expectedRows);
        if (encoded.length != expectedBytes || encoded.length > MAX_COMPRESSED
                || !Digests.sha256Hex(encoded).equals(sha256)) throw invalid();
        try (var gzip = new GZIPInputStream(new ByteArrayInputStream(encoded))) {
            byte[] raw = gzip.readNBytes(MAX_UNCOMPRESSED + 1);
            if (raw.length > MAX_UNCOMPRESSED) throw invalid();
            var lines = new String(raw, StandardCharsets.UTF_8).lines().toList();
            if (lines.size() != expectedRows + 1 || lines.stream().anyMatch(String::isBlank)) throw invalid();
            return new Lines(lines.getFirst(), lines.subList(1, lines.size()));
        } catch (IOException failure) {
            throw invalid();
        }
    }

    private static void append(StringBuilder target, String row) {
        if (row == null || row.isBlank() || row.indexOf('\n') >= 0 || row.indexOf('\r') >= 0
                || row.length() + target.length() > MAX_UNCOMPRESSED) throw invalid();
        target.append(row).append('\n');
    }

    private static void requireRowCount(int count) {
        if (count < 1 || count > MAX_ROWS) throw invalid();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("营销审计档案内容超出预算或未通过校验");
    }

    /** 返回有界的稳定 JSON 行，来源服务继续核对引用归属。 */
    public record Lines(String header, List<String> rows) {}
}
