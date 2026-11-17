package com.acme.marketing.control.application;

import com.acme.marketing.control.domain.ApprovalCase;
import com.acme.marketing.platform.error.DomainException;

/** 有界审批查询协议；游标以不可变提交顺序定位，不能用更新时间造成翻页漂移。 */
public final class ApprovalPaging {
    public static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGE_SIZE = 25;
    private static final int MAX_CURSOR_LENGTH = 64;
    private static final String INVALID_QUERY_CODE = "APPROVAL_QUERY_INVALID";
    private static final String INVALID_QUERY_MESSAGE = "审批页大小须为1–100，状态须为OPEN、APPROVED或REJECTED；游标须为有效审核单ID";
    private static final String INVALID_CURSOR_CODE = "APPROVAL_CURSOR_INVALID";
    private static final String INVALID_CURSOR_MESSAGE = "审批游标已失效或不在当前可见范围，请刷新列表";
    private ApprovalPaging() { }

    /** 不自动修正非法筛选条件，避免操作员误将另一范围的结果当成目标范围。 */
    public static Query validate(Integer limit, String status, String cursor) {
        int size = limit == null ? DEFAULT_PAGE_SIZE : limit;
        if (size < 1 || size > MAX_PAGE_SIZE || cursor != null && (cursor.isBlank()
                || cursor.length() > MAX_CURSOR_LENGTH || cursor.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c)))) {
            throw new DomainException(INVALID_QUERY_CODE, INVALID_QUERY_MESSAGE);
        }
        if (status != null) {
            try { ApprovalCase.Status.valueOf(status); }
            catch (IllegalArgumentException exception) { throw new DomainException(INVALID_QUERY_CODE, INVALID_QUERY_MESSAGE); }
        }
        return new Query(size, status, cursor);
    }

    /** 不区分不存在和越权的游标，避免泄露其它租户或组织的审核单。 */
    public static DomainException invalidCursor() { return new DomainException(INVALID_CURSOR_CODE, INVALID_CURSOR_MESSAGE); }
    public record Query(int limit, String status, String cursor) { }
}
