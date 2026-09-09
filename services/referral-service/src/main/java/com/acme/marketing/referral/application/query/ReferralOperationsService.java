package com.acme.marketing.referral.application.query;

import com.acme.marketing.platform.identity.TenantScope;
import com.acme.marketing.referral.infrastructure.persistence.mapper.ReferralOperationsMapper;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import org.springframework.stereotype.Service;

/** 管理端只读视图；主体/token/密文/内部证明不会进入DTO，所有SQL同时过滤租户组织门店。 */
@Service
public class ReferralOperationsService {
    private final ReferralOperationsMapper mapper;private final Clock clock;
    /** 使用主库当前事实，尚未接measurement时不虚构统计事件水位。 */
    public ReferralOperationsService(ReferralOperationsMapper mapper,Clock clock){this.mapper=mapper;this.clock=clock;}
    /** 参与者及人数分页；未计算的进度为null，不伪装成已验证的零人数。 */
    public Page<Participant> participants(TenantScope scope,String campaign,String after,int limit){return page(scope,campaign,after,limit,mapper::participants,Participant::participantId);}
    /** 关系及当前资格分页；BOUND不等于资格已通过。 */
    public Page<Relation> relations(TenantScope scope,String campaign,String after,int limit){return page(scope,campaign,after,limit,mapper::relations,Relation::relationId);}
    /** 奖励五维状态保持独立，202受理不能呈现为到账。 */
    public Page<Reward> rewards(TenantScope scope,String campaign,String after,int limit){return page(scope,campaign,after,limit,mapper::rewards,Reward::rewardId);}
    /** 单条SQL的主库投影统计，不代表外部事实已全部消费，成功及追回可同时计数。 */
    public Summary summary(TenantScope scope,String campaign){
        Objects.requireNonNull(scope);scope.requirePermission("referral:read");identifier(campaign);
        Counts counts=new Counts(0,0,0,0,0,0,0,0,0,0,0);
        if(!scope.organizations().isEmpty() && !scope.shops().isEmpty()){
            var values=new HashMap<String,Object>();values.put("tenant",scope.tenantId().value());values.put("campaign",campaign);values.put("organizations",scope.organizations());values.put("shops",scope.shops());counts=mapper.summary(values);
        }
        return new Summary(counts,clock.instant(),"DATABASE_PROJECTION",null);
    }
    /** 统计口径为已持久投影；valid/ever是关系数量，reward统计为奖励份数，不能混作人数。 */
    public record Counts(long participants,long relations,long projectedValidRelations,long everQualifiedRelations,long awaitingEvaluation,
            long rewards,long succeededRewards,long invalidatedRewards,long pendingCompensation,long reversedRewards,long manualReviewRewards) {}
    /** eventWatermark尚无全链路消费证明而返回null，asOf只表示本次查询结束。 */
    public record Summary(Counts counts,Instant asOf,String consistency,Long eventWatermark) {}
    private <T>Page<T> page(TenantScope scope,String campaign,String after,int limit,Function<Map<String,Object>,List<T>> query,Function<T,String> id){
        Objects.requireNonNull(scope);scope.requirePermission("referral:read");identifier(campaign);if(after!=null)identifier(after);if(limit<1 || limit>100)throw new IllegalArgumentException("limit must be 1..100");
        if(scope.organizations().isEmpty() || scope.shops().isEmpty())return new Page<>(List.of(),null,clock.instant(),"LIVE_DATABASE");
        var values=new HashMap<String,Object>();values.put("tenant",scope.tenantId().value());values.put("campaign",campaign);values.put("organizations",scope.organizations());values.put("shops",scope.shops());values.put("after",after);values.put("limit",limit+1);
        List<T> rows=query.apply(values);boolean more=rows.size()>limit;List<T> items=List.copyOf(rows.subList(0,Math.min(rows.size(),limit)));
        return new Page<>(items,more?id.apply(items.getLast()):null,clock.instant(),"LIVE_DATABASE");
    }
    private static void identifier(String value){if(value==null || !value.matches("[A-Za-z0-9._:-]{1,64}"))throw new IllegalArgumentException("invalid campaign or cursor");}
    /** 页游标取最后资源ID；asOf为查询结束时间，不宣称跨页快照或事件全部消费。 */
    public record Page<T>(List<T> items,String nextCursor,Instant asOf,String consistency){
        /** 对外只读，防止返回后被共享集合修改。 */
        public Page{items=List.copyOf(items);}
    }
    /** 固定参与版本和真实进度，不显示原主体HMAC。 */
    public record Participant(String participantId,String campaignId,String organizationId,String shopId,String definitionId,long definitionVersion,long generation,
            String state,Instant createdAt,Long validCount,Long everQualifiedCount,Long progressRevision) {}
    /** 资格尚未投影时qualificationState/reason/revision为null。 */
    public record Relation(String relationId,String participantId,Instant boundAt,Instant deadlineAt,String state,String qualificationState,String reason,
            Boolean counted,Boolean everQualified,Long qualificationRevision,Long evidenceVersion) {}
    /** 当前正交业务状态；永远不提供管理员直接改到账的入口。 */
    public record Reward(String rewardId,String participantId,String relationId,String role,String mode,String ruleId,long threshold,
            String entitlementState,String authorizationState,String riskState,String deliveryState,String compensationState,String quotaState,long revision,Instant createdAt) {}
}
