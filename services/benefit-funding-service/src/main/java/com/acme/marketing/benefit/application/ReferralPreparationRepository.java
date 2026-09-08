package com.acme.marketing.benefit.application;

import com.acme.marketing.benefit.domain.ReferralAwardPreparation;
import com.acme.marketing.benefit.domain.ReferralAwardPreparation.*;
import com.acme.marketing.benefit.application.ReferralCandidateSnapshotService.Snapshot;
import java.time.Instant;
import java.util.Optional;

/** 永久准备仓储，不提供删除/abandon；写调用必须在原短事务内，禁止持锁访问远端。 */
public interface ReferralPreparationRepository {
    /** 同身份重复准备返回原密文，不把随机加密差异视为不同业务。 */
    Stored prepare(Identity identity,Snapshot snapshot,String owner,Instant leaseUntil);
    /** 只执行封闭领域命令，不允许直接以任意nextState覆盖状态或确认。 */
    Stored apply(Identity identity,Command command);
    /** 仅为组合短事务获取固定准备行锁；不表示租约持有或新授权。 */
    Stored lock(Identity identity);
    /** 读取不解密；实际恢复必须在事务外经过保护服务并重算payload hash。 */
    Optional<Stored> find(String tenant,String sourceRequestId);
    record Stored(ReferralAwardPreparation state,Snapshot snapshot) {
        @Override public String toString(){return "StoredReferralPreparation[redacted]";}
    }
    sealed interface Command permits TakeOver,Begin,Unknown,Confirm,Reject,Accept { }
    record TakeOver(String owner,Instant until) implements Command { }
    record Begin(Owner owner,Admission admission) implements Command { }
    record Unknown(Owner owner) implements Command { }
    record Confirm(Owner owner,Receipt receipt) implements Command { }
    record Reject(Owner owner,Rejection rejection) implements Command { }
    /** 调用者须同事务插入本地intent/Outbox/expected-fact；本仓储自身不发送，也不伪造这些记录。 */
    record Accept(Owner owner,String intentId,Admission admission) implements Command { }
}
