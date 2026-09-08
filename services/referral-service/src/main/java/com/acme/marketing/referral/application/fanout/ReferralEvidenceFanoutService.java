package com.acme.marketing.referral.application.fanout;

import com.acme.marketing.platform.error.ConflictException;
import com.acme.marketing.platform.identity.TenantScope;
import com.acme.marketing.referral.application.fanout.ReferralFanoutRepository.*;
import com.acme.marketing.referral.application.qualification.ReferralProjectionEnqueuePort;
import com.acme.marketing.referral.application.qualification.ReferralProjectionEnqueuePort.Signal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** 手动内部分页派发，不注册定时器；配置显式开启且V5任务仓储就绪后才能处理。 */
@Service
@ConditionalOnProperty(name="marketing.referral.fanout.enabled",havingValue="true")
public class ReferralEvidenceFanoutService {
    private final ReferralFanoutRepository repository;
    private final ReferralProjectionEnqueuePort queue;
    private final Clock clock;
    private final long leaseSeconds;
    private final int pageSize;
    private final TransactionTemplate transactions;
    /** 零默认拒绝，不用未经确认的生产页大小或租约；1000仅为单次内存/SQL安全上界。 */
    public ReferralEvidenceFanoutService(ReferralFanoutRepository repository,ReferralProjectionEnqueuePort queue,Clock clock,
            PlatformTransactionManager manager,@Value("${marketing.referral.fanout.lease-seconds:0}") long leaseSeconds,
            @Value("${marketing.referral.fanout.page-size:0}") int pageSize) {
        this.repository=repository;this.queue=queue;this.clock=clock;this.leaseSeconds=leaseSeconds;this.pageSize=pageSize;
        transactions=new TransactionTemplate(manager);transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);transactions.setTimeout(3);
    }
    /** 领取提交后再做一页，外部调度可按租户公平调用；空结果不是所有租户均已完成。 */
    public Optional<Page> runOne(TenantScope scope,String owner){return claim(scope,owner).map(c->dispatchPage(scope,c));}
    /** 短事务领取，与后续分页分开，崩溃后可由过期租约接管同持久游标。 */
    public Optional<Claim> claim(TenantScope scope,String owner) {
        entry(scope);require(owner!=null && !owner.isBlank() && owner.length()<=128);
        return transactions.execute(tx->{Instant now=clock.instant();Task next=repository.lockNext(scope.tenantId().value(),now);if(next==null)return Optional.empty();
            require(next.tenantId().equals(scope.tenantId().value()));checkScope(scope,current(next));
            now=clock.instant();require(next.status()==Status.PENDING || next.status()==Status.PROCESSING && !now.isBefore(next.leaseUntil()));
            // V4扫描列为微秒：租约截止只向下取整，判定仍用raw now，最多提前失效，不复活过期租约。
            Instant until=now.plusSeconds(leaseSeconds).truncatedTo(ChronoUnit.MICROS);require(until.isAfter(now));
            Task claimed=repository.claim(next,owner,until,now);live(claimed,clock.instant());return Optional.of(new Claim(claimed));});
    }
    /** 页游标、任务enqueue及完成CAS同事务；新证据重置fence后旧Claim无权完成。 */
    public Page dispatchPage(TenantScope scope,Claim claim) {
        entry(scope);Objects.requireNonNull(claim);require(claim.task().tenantId().equals(scope.tenantId().value()));
        return transactions.execute(tx->{Task locked=repository.lock(claim.task().tenantId(),claim.task().resourceId());
            require(locked!=null && locked.equals(claim.task()));live(locked,clock.instant());Current basis=current(locked);checkScope(scope,basis);
            List<Target> candidates=repository.targets(locked,basis,pageSize+1);require(candidates!=null && candidates.size()<=pageSize+1);
            String cursor=locked.cursor();int count=Math.min(pageSize,candidates.size());
            for(int i=0;i<count;i++){
                Target target=candidates.get(i);require(target.tenantId().equals(locked.tenantId()) && (cursor==null || target.relationId().compareTo(cursor)>0));
                scope.requireOrganization(target.organizationId());scope.requireShop(target.shopId());
                queue.enqueue(new Signal(locked.tenantId(),target.participantId(),target.relationId(),locked.resourceId(),locked.desiredVersion(),locked.reason()));cursor=target.relationId();
            }
            live(locked,clock.instant());boolean more=candidates.size()>pageSize;repository.finish(locked,cursor,more,clock.instant());
            live(locked,clock.instant());return new Page(locked.resourceId(),locked.desiredVersion(),count,more);
        });
    }
    private Current current(Task task){Current c=repository.readCurrent(task.tenantId(),task.resourceId());require(c!=null && c.tenantId().equals(task.tenantId()) && c.resourceId().equals(task.resourceId()) && c.rowVersion()==task.desiredVersion());return c;}
    private static void checkScope(TenantScope scope,Current current){scope.requireOrganization(current.organizationId());scope.requireShop(current.shopId());}
    private void entry(TenantScope scope){Objects.requireNonNull(scope);scope.requirePermission("referral:project");require(!TransactionSynchronizationManager.isActualTransactionActive() && leaseSeconds>0 && pageSize>0 && pageSize<=1000);}
    private static void live(Task task,Instant now){require(task.status()==Status.PROCESSING && now.isBefore(task.leaseUntil()));}
    private static void require(boolean ok){if(!ok)throw new ConflictException("REFERRAL_FANOUT_UNAVAILABLE","fanout permission, lease or persistent version unavailable");}
    /** Claim只作租约引用，必须锁后重新读取完整Task匹配，不能把调用方结构当当前事实。 */
    public record Claim(Task task){public Claim{Objects.requireNonNull(task);}}
    /** 这里只报告入队数量与是否仍有下一页，不报告资格或奖励成功。 */
    public record Page(String resourceId,long desiredVersion,int enqueued,boolean more){}
}
