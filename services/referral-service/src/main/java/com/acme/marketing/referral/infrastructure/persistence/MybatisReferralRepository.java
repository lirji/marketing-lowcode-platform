package com.acme.marketing.referral.infrastructure.persistence;
import com.acme.marketing.referral.application.ReferralRepository;
import com.acme.marketing.referral.application.TrustedReferralSubjectPort.Subject;
import com.acme.marketing.referral.domain.ReferralParticipant;
import com.acme.marketing.referral.infrastructure.persistence.mapper.ReferralMapper;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.stereotype.Repository;
/** 关系库实现维持调用方本地事务；不重试不确定写入、不另开提交。 */
@Repository
public class MybatisReferralRepository implements ReferralRepository {
    private final ReferralMapper mapper;
    private static final DateTimeFormatter SQL_TIME=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS").withZone(ZoneOffset.UTC);
    /** 只依赖本服务Mapper，禁止跨库或原平台领域依赖。 */
    public MybatisReferralRepository(ReferralMapper mapper) { this.mapper=mapper; }
    /** 每次只读既存锚点的共享锁，不更新全局热行或自动接受新索引密钥版本。 */
    @Override public Long subjectIndexVersion(String tenant) { return mapper.subjectIndexVersion(tenant); }
    /** 锁命令键后当前读，唯一冲突不吞掉其他数据库约束异常。 */
    @Override public Command lockCommand(String tenant,String subjectKey,String key,String requestHash,Instant now) {
        var values=Map.<String,Object>of("tenant",tenant,"subjectKey",subjectKey,"key",key,"requestHash",requestHash,"now",SQL_TIME.format(now));
        mapper.reserveCommand(values); return Objects.requireNonNull(mapper.command(values));
    }
    /** 命令锁之后取得活动主体角色，符合后续绑定锁序。 */
    @Override public Role lockRole(String tenant,String campaign,Subject subject,Instant now) {
        var values=Map.<String,Object>of("tenant",tenant,"campaign",campaign,"subjectKey",subject.subjectKey(),"keyVersion",subject.keyVersion(),"now",SQL_TIME.format(now));
        mapper.reserveRole(values); return Objects.requireNonNull(mapper.role(values));
    }
    /** 角色锁已持有，返回不可变原版本；缺行不能伪造历史成功。 */
    @Override public ReferralParticipant participant(String tenant,String participantId) { return mapper.participant(tenant,participantId); }
    /** 主体密文只写入专用列，不进入响应、审计或消息。 */
    @Override public void insert(ReferralParticipant participant,Subject subject) {
        var values=new HashMap<String,Object>(); values.put("p",participant); values.put("subjectKey",subject.subjectKey());
        values.put("keyVersion",subject.keyVersion()); values.put("cipher",subject.cipher()); values.put("encryptionKeyId",subject.encryptionKeyId());
        values.put("now",SQL_TIME.format(participant.createdAt()));
        one(mapper.insertParticipant(values)); one(mapper.attachRole(values));
    }
    /** 只从首次未完成回执推进到固定参与者引用。 */
    @Override public void complete(String tenant,String subjectKey,String key,String participantId,Instant now) {
        one(mapper.complete(Map.of("tenant",tenant,"subjectKey",subjectKey,"key",key,"participantId",participantId,"now",SQL_TIME.format(now))));
    }
    /** 使用参与者作为聚合，审计/Outbox都跟随加入事务提交。 */
    @Override public void appendJoined(ReferralParticipant p,String actorId,String traceId,String payload,String payloadHash) {
        var values=Map.<String,Object>of("p",p,"auditId",UUID.randomUUID().toString(),"actorId",actorId,"traceId",traceId,
                "payload",payload,"payloadHash",payloadHash,"eventId",UUID.randomUUID().toString(),"now",SQL_TIME.format(p.createdAt()));
        one(mapper.audit(values)); one(mapper.outbox(values));
    }
    private static void one(int rows) { if(rows!=1) throw new IllegalStateException("referral persistence invariant failed"); }
}
