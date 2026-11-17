package com.acme.marketing.audience.application;

import com.acme.marketing.platform.error.DomainException;

/** 人群资产查询边界；ID稳定排序允许版本更新，翻页不承诺跨请求历史快照。 */
public final class AudiencePaging {
    public static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGE_SIZE = 25;
    private static final int MAX_TEXT_LENGTH = 128;
    private static final String INVALID_QUERY = "AUDIENCE_QUERY_INVALID";
    private static final String INVALID_QUERY_MESSAGE = "人群页大小须为1–100，搜索词和游标最长128字符且不能含控制字符；游标不能为空";
    private static final String INVALID_CURSOR = "AUDIENCE_CURSOR_INVALID";
    private static final String INVALID_CURSOR_MESSAGE = "人群游标不存在于当前租户，请刷新列表";
    private AudiencePaging() { }

    /** 搜索采用字面包含匹配，不把百分号或下划线解释为SQL通配符。 */
    public static Query validate(Integer limit, String query, String cursor) {
        int size = limit == null ? DEFAULT_PAGE_SIZE : limit;
        if (size < 1 || size > MAX_PAGE_SIZE || invalidText(query) || invalidText(cursor)
                || cursor != null && cursor.isBlank()) {
            throw new DomainException(INVALID_QUERY, INVALID_QUERY_MESSAGE);
        }
        return new Query(size, query == null ? "" : query.trim(), cursor);
    }

    /** 不区分未知ID与其他租户的ID，避免游标查询成为存在性探针。 */
    public static DomainException invalidCursor() { return new DomainException(INVALID_CURSOR, INVALID_CURSOR_MESSAGE); }
    private static boolean invalidText(String value) {
        return value != null && (value.length() > MAX_TEXT_LENGTH || value.chars().anyMatch(Character::isISOControl));
    }
    public record Query(int limit, String query, String cursor) { }
}
