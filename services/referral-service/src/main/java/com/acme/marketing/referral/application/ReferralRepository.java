package com.acme.marketing.referral.application;
import com.acme.marketing.referral.domain.ReferralParticipant;
import com.acme.marketing.referral.application.TrustedReferralSubjectPort.Subject;
import java.time.Instant;
/** 同一主库事务的参与者仓储，不进行跨服务查询或隐式提交。 */
public interface ReferralRepository {
    /** 读取受控迁移预置的租户索引版本锚点并持有共享锁；没有锚点时拒绝，运行时不创建/轮换。 */
    Long subjectIndexVersion(String tenant);
    /** 先锁命令键；永久回执仅引用参与者，不保存可过期的敏感响应。 */
    Command lockCommand(String tenant,String subjectKey,String key,String requestHash,Instant now);
    /** 主体活动角色是并发参与及后续互邀约束的唯一锁点。 */
    Role lockRole(String tenant,String campaign,Subject subject,Instant now);
    /** 在角色锁下读取原参与者快照；不改变其固定规则版本。 */
    ReferralParticipant participant(String tenant,String participantId);
    /** 插入不可变参与者，并把当前INVITER角色关联到它。 */
    void insert(ReferralParticipant participant,Subject subject);
    /** 原参与者引用与命令同事务完成，保留同键异内容冲突证据。 */
    void complete(String tenant,String subjectKey,String key,String participantId,Instant now);
    /** 同事务追加去主体化审计与内部Outbox，失败必须让参与事务回滚。 */
    void appendJoined(ReferralParticipant participant,String actorId,String traceId,String payload,String payloadHash);
    /** 命令可能尚在本事务内首次创建，participantId为空表示未完成。 */
    record Command(String requestHash,String participantId) {}
    /** 角色锁防止同活动邀请人与被邀请人身份互斥失效。 */
    record Role(String role,String participantId,long keyVersion) {}
}
