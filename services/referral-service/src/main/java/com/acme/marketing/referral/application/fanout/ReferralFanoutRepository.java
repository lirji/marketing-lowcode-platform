package com.acme.marketing.referral.application.fanout;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** V4任务分页仓储：只锁fanout，不锁current/participant/relation，避免与资格事务形成环。 */
public interface ReferralFanoutRepository {
    /** 租户内SKIP LOCKED领取候选，所有读取/写入由调用方短事务承载。 */
    Task lockNext(String tenant,Instant now);
    /** 只锁指定fanout行，旧Claim必须再与当前版本匹配。 */
    Task lock(String tenant,String resource);
    /** 非锁定一致性读，只读受保护状态Header；不在这里解密。 */
    Current readCurrent(String tenant,String resource);
    /** 修改租约的CAS不得改任何父引用键，序号溢出由Java精确算术拒绝。 */
    Task claim(Task before,String owner,Instant until,Instant now);
    /** 当前HMAC/Scope关系与既有资格依赖UNION，两个分支都JOIN永久关系；按relationId分页。 */
    List<Target> targets(Task task,Current current,int limit);
    /** 页结果与enqueue同事务；CAS失败必须整体回滚，不能让旧完成覆盖新目标。 */
    void finish(Task before,String cursor,boolean more,Instant now);

    enum Status { PENDING,PROCESSING,DONE }
    record Task(String tenantId,String resourceId,long desiredVersion,String reason,Status status,String cursor,
            String owner,Instant leaseUntil,long fence,long rowVersion) {
        public Task {text(tenantId);text(resourceId);text(reason);Objects.requireNonNull(status);require(desiredVersion>0 && fence>0 && rowVersion>0);
            if(status==Status.PROCESSING){text(owner);Objects.requireNonNull(leaseUntil);}else require(owner==null && leaseUntil==null);}
        @Override public String toString(){return "ReferralFanoutTask["+status+"]";}
    }
    record Current(String tenantId,String resourceId,String subjectKey,long keyVersion,String organizationId,String shopId,long rowVersion) {
        public Current {text(tenantId);text(resourceId);text(organizationId);text(shopId);require(subjectKey!=null && subjectKey.matches("[a-f0-9]{64}") && keyVersion>0 && rowVersion>0);}
        @Override public String toString(){return "ReferralFanoutCurrent[redacted]";}
    }
    record Target(String tenantId,String participantId,String relationId,String organizationId,String shopId) {
        public Target {text(tenantId);text(participantId);text(relationId);text(organizationId);text(shopId);}
        @Override public String toString(){return "ReferralFanoutTarget[redacted]";}
    }
    private static void text(String v){require(v!=null && !v.isBlank());}
    private static void require(boolean ok){if(!ok)throw new IllegalArgumentException("invalid fanout record");}
}
