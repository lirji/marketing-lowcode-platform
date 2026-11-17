package com.acme.marketing.journeyservice.application;

import java.util.Optional;

/** Journey服务拥有的控制授权接收投影，不读写控制库或既有单清单路由。 */
public interface BundleDirectiveRepository {
    /** 原子争取head行并锁定，必须是接收事务首个数据库访问。 */
    Long lockHead(Key key, String now);
    /** 精确历史读取有界为一条，不扫描其他活动。 */
    Optional<StoredDirective> history(Key key, long sequence);
    /** 检查指令编号不能跨激活序号复用。 */
    Optional<Long> sequenceForId(Key key, String directiveId);
    /** 不可变历史写入，调用方已持有head锁。 */
    void save(Key key, StoredDirective value, String recordedBy);
    /** 用原序号条件更新head，不能把丢失更新当作成功。 */
    int advance(Key key, Long expected, long sequence, String now);
    /** 联表读取一个一致的最新接收记录。 */
    Optional<StoredDirective> latest(Key key);

    record Key(String tenantId, String environment, String cell, String namespace, String campaignId) { }
    record StoredDirective(long activationSequence, String directiveId, String directiveJson, String recordedAt) { }
}
