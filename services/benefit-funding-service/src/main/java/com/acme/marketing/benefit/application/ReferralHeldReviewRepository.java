package com.acme.marketing.benefit.application;
import com.acme.marketing.benefit.application.ReferralIntakeIdentityPort.Binding;
import java.time.Instant;
/** 永久固定身份下的最近一次复核观察；CHECKED不能代替真实发送瞬间的在线许可。 */
public interface ReferralHeldReviewRepository {
    /** 持有prep/context锁后写入，序号CAS且取消/隔离只能保持或加强。 */
    Observation record(Binding binding,Status status,long currentRevision,long cancelRevision,Instant checkedAt,Instant validUntil);
    Observation find(String tenant,String request);
    enum Status { UNKNOWN,CHECKED,BLOCKED,CANCELLED,QUARANTINED }
    record Observation(long sequence,Status status,long currentRevision,long cancelRevision,Instant checkedAt,Instant validUntil){}
}
