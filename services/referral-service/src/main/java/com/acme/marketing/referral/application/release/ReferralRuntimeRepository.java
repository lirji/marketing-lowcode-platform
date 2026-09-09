package com.acme.marketing.referral.application.release;

import java.time.Instant;

/** 本地运行序号与不可变审计；两个流分别按 activation slot 和 tenant/namespace 熔断范围隔离。 */
public interface ReferralRuntimeRepository {
    /** 非锁读已安装事实，激活之前必须重新验证保存的签名和载荷。 */
    ReferralReleaseRepository.Stored installed(Key slot, long generation);
    /** 同事务保留游标并排他锁读，不允许应用层无锁覆盖。 */
    Cursor lock(Key key);
    /** 非锁读已提交游标，恢复/本地检查仍须重验签名。 */
    Cursor current(Key key);
    /** 原子追加审计并按原序号推进游标；失败由调用方事务整体回滚。 */
    void advance(Key key, long expectedSequence, long sequence, String json, Instant now);

    /** KILL 的环境/cell 固定为空，因为已有签名合同的范围只有 tenant/namespace。 */
    record Key(String tenantId, String kind, String environment, String cell, String namespace) { }
    /** 序号零表示尚未有可信指令；不能据此默认关闭熔断。 */
    record Cursor(long sequence, String directiveJson) { }
}
