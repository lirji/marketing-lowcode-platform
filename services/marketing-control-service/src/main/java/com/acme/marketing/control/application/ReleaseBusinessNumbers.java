package com.acme.marketing.control.application;

import com.acme.marketing.platform.identity.BusinessNumber;
import java.time.Instant;
import java.util.function.Consumer;
import org.springframework.dao.DuplicateKeyException;

/** 发布记录与冻结签名引用在同一事实内保存。 */
final class ReleaseBusinessNumbers {
    private ReleaseBusinessNumbers() {}

    /** 仅在发布记录的业务号唯一键发生碰撞时重新选号。 */
    static String issue(BusinessNumber.Type type, Instant createdAt, String index, Consumer<String> insert) {
        for (int attempt = 0; attempt < BusinessNumber.MAX_ISSUE_ATTEMPTS; attempt++) {
            String number = BusinessNumber.next(type, createdAt);
            try {
                insert.accept(number);
                return number;
            } catch (DuplicateKeyException duplicate) {
                String message = duplicate.getMostSpecificCause().getMessage();
                if (attempt == BusinessNumber.MAX_ISSUE_ATTEMPTS - 1 || message == null
                        || (!message.endsWith("." + index + "'") && !message.endsWith("'" + index + "'"))) throw duplicate;
            }
        }
        throw new IllegalStateException("release business number allocation exhausted");
    }
}
