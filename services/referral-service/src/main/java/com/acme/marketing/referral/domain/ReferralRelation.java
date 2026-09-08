package com.acme.marketing.referral.domain;
import java.time.Instant;
/** 永久首次有效归因；BOUND只表示接受关系，资格/奖励仍待后续权威事实评估。 */
public record ReferralRelation(String tenantId,String relationId,String campaignId,String organizationId,String shopId,
        String participantId,String tokenId,String definitionId,long definitionVersion,long generation,String artifactId,String policyHash,
        Instant boundAt,Instant qualifyDeadline,String consentVersion,String consentHash,String state) {}
