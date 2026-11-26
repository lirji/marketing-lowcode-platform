package com.acme.marketing.referral.infrastructure.persistence;

import com.acme.marketing.platform.identity.BusinessNumber;
import java.time.Instant;
import java.util.Map;
import java.util.function.ToIntFunction;
import org.springframework.dao.DuplicateKeyException;

/** 运营编号与原参与、归因及奖励证据一并提交。 */
final class ReferralBusinessNumbers {
    private ReferralBusinessNumbers() {}

    /** 激活关系或持久奖励仍使用原去重键，只有业务号唯一键冲突才重新生成。 */
    static void issue(BusinessNumber.Type type, Instant createdAt, String index, Map<String,Object> values,
            ToIntFunction<Map<String,Object>> insert) {
        for (int attempt = 0; attempt < BusinessNumber.MAX_ISSUE_ATTEMPTS; attempt++) {
            values.put("businessNo", BusinessNumber.next(type, createdAt));
            try {
                if (insert.applyAsInt(values) != 1) throw new IllegalStateException("referral number write failed");
                return;
            } catch (DuplicateKeyException duplicate) {
                String message = duplicate.getMostSpecificCause().getMessage();
                if (attempt == BusinessNumber.MAX_ISSUE_ATTEMPTS - 1 || message == null
                        || (!message.endsWith("." + index + "'") && !message.endsWith("'" + index + "'"))) throw duplicate;
            }
        }
        throw new IllegalStateException("referral business number allocation exhausted");
    }
}
