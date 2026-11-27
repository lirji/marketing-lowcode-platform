package com.acme.marketing.platform.web.archive.persistence;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 将来源 SQL 的白名单 JSON 保留为结构化证据，避免归档后丢掉字段类型。 */
public final class AuditArchiveFactJsonHandler extends BaseTypeHandler<JsonNode> {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Override
    public void setNonNullParameter(PreparedStatement statement, int index, JsonNode fact, JdbcType jdbcType) throws SQLException {
        statement.setString(index, JSON.writeValueAsString(fact));
    }

    @Override
    public JsonNode getNullableResult(ResultSet row, String column) throws SQLException {
        return decode(row.getString(column));
    }

    @Override
    public JsonNode getNullableResult(ResultSet row, int index) throws SQLException {
        return decode(row.getString(index));
    }

    @Override
    public JsonNode getNullableResult(CallableStatement row, int index) throws SQLException {
        return decode(row.getString(index));
    }

    private static JsonNode decode(String value) {
        return value == null ? null : JSON.readTree(value);
    }
}
