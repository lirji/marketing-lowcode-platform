package com.acme.marketing.referral.infrastructure.persistence;

import com.acme.marketing.referral.application.fanout.ReferralFanoutRepository;
import com.acme.marketing.referral.infrastructure.persistence.mapper.ReferralFanoutMapper;
import com.acme.marketing.referral.infrastructure.persistence.mapper.ReferralFanoutMapper.TaskRow;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 只锁现有fanout引用，不新建父引用；资格任务同事务写入由独立内部Port负责。 */
@Repository
public class MybatisReferralFanoutRepository implements ReferralFanoutRepository {
    private static final DateTimeFormatter FORMAT=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");
    private final ReferralFanoutMapper mapper;
    public MybatisReferralFanoutRepository(ReferralFanoutMapper mapper){this.mapper=mapper;}
    @Override public Task lockNext(String tenant,Instant now){tx();var v=new HashMap<String,Object>();v.put("tenant",tenant);v.put("now",time(now));return task(mapper.next(v));}
    @Override public Task lock(String tenant,String resource){tx();return task(mapper.task(key(tenant,resource)));}
    @Override public Current readCurrent(String tenant,String resource){return mapper.current(key(tenant,resource));}
    @Override public Task claim(Task before,String owner,Instant until,Instant now){
        tx();var v=version(before);v.put("owner",owner);v.put("until",time(until));v.put("now",time(now));
        long fence=Math.incrementExact(before.fence()),version=Math.incrementExact(before.rowVersion());v.put("nextFence",fence);v.put("nextVersion",version);one(mapper.claim(v));
        return new Task(before.tenantId(),before.resourceId(),before.desiredVersion(),before.reason(),Status.PROCESSING,before.cursor(),owner,until,fence,version);
    }
    @Override public List<Target> targets(Task task,Current current,int limit){
        tx();var v=key(task.tenantId(),task.resourceId());v.put("subject",current.subjectKey());v.put("keyVersion",current.keyVersion());
        v.put("organization",current.organizationId());v.put("shop",current.shopId());v.put("cursor",task.cursor());v.put("limit",limit);return mapper.targets(v);
    }
    @Override public void finish(Task before,String cursor,boolean more,Instant now){
        tx();var v=version(before);v.put("cursor",cursor);v.put("status",more?"PENDING":"DONE");v.put("owner",before.owner());v.put("now",time(now));v.put("nextVersion",Math.incrementExact(before.rowVersion()));one(mapper.finish(v));
    }
    private static Map<String,Object> key(String tenant,String resource){var v=new HashMap<String,Object>();v.put("tenant",tenant);v.put("resource",resource);return v;}
    private static Map<String,Object> version(Task t){var v=key(t.tenantId(),t.resourceId());v.put("version",t.rowVersion());v.put("fence",t.fence());v.put("desired",t.desiredVersion());return v;}
    private static Task task(TaskRow r){return r==null?null:new Task(r.tenantId(),r.resourceId(),r.desiredVersion(),r.reason(),Status.valueOf(r.status()),r.cursor(),r.owner(),r.leaseUntil()==null?null:LocalDateTime.parse(r.leaseUntil(),FORMAT).toInstant(ZoneOffset.UTC),r.fence(),r.rowVersion());}
    private static String time(Instant value){return FORMAT.format(value.atOffset(ZoneOffset.UTC));}
    private static void tx(){if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("fanout writes require owning transaction");}
    private static void one(int rows){if(rows!=1)throw new IllegalStateException("fanout compare-and-set rejected");}
}
