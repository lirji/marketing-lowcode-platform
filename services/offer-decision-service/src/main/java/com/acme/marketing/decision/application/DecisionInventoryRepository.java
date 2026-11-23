package com.acme.marketing.decision.application;

/** 物理部署位置跨活动共享；登记与业务参与使用不同的锁。 */
public interface DecisionInventoryRepository {
    record Slot(String environment, String cell, String namespace) { }
    record Position(long sequence, String digest) { }

    /** 在登记事务内串行化同位置的首次上报。 */
    void ensureHead(Slot slot);
    /** 等待其他登记完成后检查已提交序号。 */
    Position lockHead(Slot slot);
    /** 单语句查询当前位置，空位置返回 null。 */
    Position current(Slot slot);
    /** 历史只追加，异常将与 head 推进一起回滚。 */
    void append(Slot slot, Position position, String json,
            String actor, String now);
    /** 持锁更新时保留原序号条件。 */
    int advance(Slot slot, long previous, Position position);
}
