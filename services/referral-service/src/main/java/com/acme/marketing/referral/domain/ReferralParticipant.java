package com.acme.marketing.referral.domain;
import java.time.Instant;
/** 永久参与快照，不含主体HMAC/密文，供受授权内部调用方重放首次固定版本。 */
public record ReferralParticipant(String tenantId,String participantId,String campaignId,String organizationId,String shopId,
        String definitionId,long definitionVersion,long generation,String artifactId,String policyHash,long routeEpoch,
        String state,Instant createdAt) {}
