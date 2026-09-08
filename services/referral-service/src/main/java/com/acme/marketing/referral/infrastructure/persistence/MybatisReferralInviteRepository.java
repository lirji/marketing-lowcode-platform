package com.acme.marketing.referral.infrastructure.persistence;
import com.acme.marketing.referral.application.ReferralInviteRepository;
import com.acme.marketing.referral.application.TokenBindingReadPort;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.acme.marketing.referral.infrastructure.persistence.mapper.ReferralInviteMapper;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.stereotype.Repository;
/** token/专用密文回执/审计只加入调用方事务，不依赖明文平台API响应缓存。 */
@Repository
public class MybatisReferralInviteRepository implements ReferralInviteRepository,TokenBindingReadPort {
    private final ReferralInviteMapper mapper;
    private static final DateTimeFormatter SQL_TIME=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS").withZone(ZoneOffset.UTC);
    /** 本服务独立Mapper，不跨服务读库。 */
    public MybatisReferralInviteRepository(ReferralInviteMapper mapper) { this.mapper=mapper; }
    /** 事务外观察只减少不必要的KMS加密；正式裁决仍依赖锁后读。 */
    @Override public Replay findReplay(String tenant,String subject,String key) { return mapper.replay(Map.of("tenant",tenant,"subject",subject,"key",key,"lock",false)); }
    /** 锁内插入空回执与业务结果同提交，不持久化PROCESSING中间成功。 */
    @Override public Replay lockReplay(String tenant,String subject,String key,String hash,Instant now) {
        var values=Map.<String,Object>of("tenant",tenant,"subject",subject,"key",key,"hash",hash,"now",SQL_TIME.format(now),"lock",true);
        mapper.reserve(values);return Objects.requireNonNull(mapper.replay(values));
    }
    /** 锁内先读取参与者行，所有权在同一行锁保护下稳定。 */
    @Override public Owned participant(String tenant,String participantId,boolean lock) {
        var values=Map.<String,Object>of("tenant",tenant,"participantId",participantId,"lock",lock);
        var p=mapper.participant(values);if(p==null) return null;
        var owner=mapper.ownership(values);if(owner==null) return null;
        return new Owned(p,owner.subjectKey(),owner.keyVersion());
    }
    /** 只记录不含token的审计摘要，三次写入任一失败让外层事务回滚。 */
    @Override public void save(Replay replay,String participantId,String actorId,String traceId,Instant now) {
        var values=Map.<String,Object>of("r",replay,"participantId",participantId,"actorId",actorId,"traceId",traceId,"auditId",UUID.randomUUID().toString(),
                "now",SQL_TIME.format(now),"tokenExpiresAt",SQL_TIME.format(replay.tokenExpiresAt()),"replayUntil",SQL_TIME.format(replay.replayUntil()));
        one(mapper.insertToken(values));one(mapper.complete(values));one(mapper.audit(values));
    }
    /** token身份保留永久，是否可用由服务时点判断。 */
    @Override public Token token(String tenant,String hash) { return mapper.token(tenant,hash); }
    /** 绑定前非锁定位复用同一token身份读取，不授予绑定资格。 */
    @Override public Token locate(String tenant,String hash) { return mapper.token(tenant,hash); }
    /** 只允许participant之后的事务内锁读，禁止外层无锁调用伪装成最终裁决。 */
    @Override public Token lock(String tenant,String hash) {
        if(!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("token binding lock requires transaction");
        return mapper.lockToken(tenant,hash);
    }
    private static void one(int rows) { if(rows!=1) throw new IllegalStateException("invite persistence invariant failed"); }
}
