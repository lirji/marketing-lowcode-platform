package com.acme.marketing.referral.infrastructure.persistence;
import com.acme.marketing.referral.application.ReferralBindingRepository;
import com.acme.marketing.referral.application.TrustedReferralSubjectPort.Subject;
import com.acme.marketing.referral.domain.ReferralRelation;
import com.acme.marketing.referral.infrastructure.persistence.mapper.ReferralBindingMapper;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.stereotype.Repository;
/** 首绑持久化只参与调用方事务，SQL唯一键与排序角色锁共同约束永久归因。 */
@Repository
public class MybatisReferralBindingRepository implements ReferralBindingRepository {
    private final ReferralBindingMapper mapper;
    private final com.acme.marketing.referral.application.qualification.ReferralProjectionEnqueuePort projections;
    private static final DateTimeFormatter SQL_TIME=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS").withZone(ZoneOffset.UTC);
    /** 本服务本地主库Mapper，不执行远程调用或内部嵌套提交。 */
    public MybatisReferralBindingRepository(ReferralBindingMapper mapper,com.acme.marketing.referral.application.qualification.ReferralProjectionEnqueuePort projections) { this.mapper=mapper;this.projections=projections; }
    /** 相同命令请求在当前读下复用原关系，不覆盖payload。 */
    @Override public Command lockCommand(String tenant,String invitee,String key,String hash,Instant now) {
        var values=Map.<String,Object>of("tenant",tenant,"invitee",invitee,"key",key,"hash",hash,"now",SQL_TIME.format(now));
        mapper.reserveCommand(values);return Objects.requireNonNull(mapper.command(values));
    }
    /** 邀请人角色缺失返回null，不自动修复或合并。 */
    @Override public Role lockInviter(String tenant,String campaign,String subject) { return mapper.role(Map.of("tenant",tenant,"campaign",campaign,"subject",subject)); }
    /** 对已有角色只取得锁，角色不符由应用层拒绝并回滚本次占位。 */
    @Override public Role lockInvitee(String tenant,String campaign,Subject subject,Instant now) {
        var values=Map.<String,Object>of("tenant",tenant,"campaign",campaign,"subject",subject.subjectKey(),"keyVersion",subject.keyVersion(),"now",SQL_TIME.format(now));
        mapper.reserveInvitee(values);return Objects.requireNonNull(mapper.role(values));
    }
    /** 角色及token锁后使用唯一键查询原归因。 */
    @Override public ReferralRelation relation(String tenant,String campaign,String invitee) { return mapper.relation(Map.of("tenant",tenant,"campaign",campaign,"invitee",invitee)); }
    /** 永久命令回放只查同租户原关系。 */
    @Override public ReferralRelation relationById(String tenant,String relationId) { return mapper.relation(Map.of("tenant",tenant,"relationId",relationId)); }
    /** 密文主体仅入专用列；关系/角色/审计/Outbox严格同事务。 */
    @Override public void save(ReferralRelation relation,Subject invitee,String actorId,String traceId,String payload,String payloadHash) {
        var values=new HashMap<String,Object>();values.put("r",relation);values.put("subject",invitee.subjectKey());values.put("keyVersion",invitee.keyVersion());
        values.put("cipher",invitee.cipher());values.put("encryptionKeyId",invitee.encryptionKeyId());values.put("actorId",actorId);values.put("traceId",traceId);
        values.put("payload",payload);values.put("payloadHash",payloadHash);values.put("auditId",UUID.randomUUID().toString());values.put("eventId",UUID.randomUUID().toString());
        values.put("now",SQL_TIME.format(relation.boundAt()));values.put("deadline",SQL_TIME.format(relation.qualifyDeadline()));
        one(mapper.insertRelation(values));one(mapper.attachInvitee(values));one(mapper.audit(values));one(mapper.outbox(values));
        // 新BOUND与待评估任务同事务；不改变历史原成功命令的永久回放路径。
        projections.enqueue(new com.acme.marketing.referral.application.qualification.ReferralProjectionEnqueuePort.Signal(relation.tenantId(),relation.participantId(),relation.relationId(),null,0,"BOUND"));
    }
    /** 新回执完成时只引用既有或本次创建的永久关系。 */
    @Override public void complete(String tenant,String invitee,String key,String relationId,Instant now) {
        one(mapper.complete(Map.of("tenant",tenant,"invitee",invitee,"key",key,"relationId",relationId,"now",SQL_TIME.format(now))));
    }
    private static void one(int rows) { if(rows!=1) throw new IllegalStateException("binding persistence invariant failed"); }
}
