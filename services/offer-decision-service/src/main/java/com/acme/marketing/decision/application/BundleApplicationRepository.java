package com.acme.marketing.decision.application;

import com.acme.marketing.decision.application.BundleAdmissionRepository.Key;
import com.acme.marketing.decision.application.DecisionInventoryRepository.Slot;
import java.util.List;

/** 组合应用写入和真实部署证据读取均在 Decision 权威库完成。 */
public interface BundleApplicationRepository {
    record Inventory(long sequence, String digest, String document) { }
    /** 跨全部命名空间串行应用，首次发布也有确定顺序。 */
    int serialize(Key key);
    /** 读取当前共享准入指针。 */
    Long current(Key key);
    /** 只锁定已有应用仲裁位置，未知活动查询不会新建记录。 */
    String lockApplication(Key key);
    /** 等待在途准入结束，随后重验时间窗口。 */
    Long lockPointer(Key key);
    /** 持共享锁读取当前部署声明，直到应用事务提交。 */
    Inventory inventory(Slot slot);
    /** 同一实例和部署版本保留一份签名证据。 */
    void attest(Key key, String namespace, long sequence, long deployment, String instance, String json);
    /** 同一副本重试保留原回执与首次时间。 */
    String receipt(Key key, String namespace, long sequence, long deployment, String instance);
    /** 只读该版本的副本回执。 */
    List<String> receipts(Key key, String namespace, long sequence, long deployment);
    /** 回滚目标必须是该活动曾实际应用的不可变组合。 */
    boolean previouslyApplied(Key key, String bundleId, long version, String hash);
    /** 不可变应用历史与指针在同一事务提交。 */
    int append(Key key, long sequence, String directiveId, String json, String evidence, String now);
    /** 首次设置准入指针。 */
    int first(Key key, long sequence, String now);
    /** 用原序号条件推进既有准入指针。 */
    int advance(Key key, long previous, long sequence, String now);
    /** 真实应用后关闭本站无凭证路由。 */
    int protectSlot(Key key, String namespace);
    /** 核对旧入口防绕过标记，行缺失不能算作成功。 */
    Boolean protectedSlot(Key key, String namespace);
}
