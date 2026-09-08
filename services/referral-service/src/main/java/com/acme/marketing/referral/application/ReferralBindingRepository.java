package com.acme.marketing.referral.application;
import com.acme.marketing.referral.domain.ReferralRelation;
import com.acme.marketing.referral.application.TrustedReferralSubjectPort.Subject;
import java.time.Instant;
/** 首绑同库仓储，角色/关系/命令/审计/Outbox全部由应用单一事务裁决。 */
public interface ReferralBindingRepository {
    /** 永久命令键锁，旧payload永远不能被新请求覆盖。 */
    Command lockCommand(String tenant,String invitee,String key,String hash,Instant now);
    /** 邀请人必须已有角色，不自动创建或转换角色。 */
    Role lockInviter(String tenant,String campaign,String subject);
    /** 只尝试创建INVITEE；既有INVITER会由上层拒绝且不会改写。 */
    Role lockInvitee(String tenant,String campaign,Subject subject,Instant now);
    /** 在角色及token锁之后读取好友已有永久关系。 */
    ReferralRelation relation(String tenant,String campaign,String invitee);
    /** 原成功命令只按租户读取原关系，不重新绑定。 */
    ReferralRelation relationById(String tenant,String relationId);
    /** 保存关系与密文主体，关联INVITEE角色，追加审计Outbox；失败须全部回滚。 */
    void save(ReferralRelation relation,Subject invitee,String actorId,String traceId,String payload,String payloadHash);
    /** 完成原关系引用，不保存敏感API明文响应。 */
    void complete(String tenant,String invitee,String key,String relationId,Instant now);
    /** 未提交首次命令relationId为空，不能作为外部成功。 */
    record Command(String requestHash,String relationId) {}
    /** 同活动角色互斥的锁后当前投影。 */
    record Role(String role,String participantId,String relationId,long keyVersion) {}
}
