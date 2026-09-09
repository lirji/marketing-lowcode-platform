package com.acme.marketing.referral.infrastructure.persistence;

import com.acme.marketing.contracts.referral.ReferralAwardIdentity;
import com.acme.marketing.platform.crypto.*;
import com.acme.marketing.referral.*;
import com.acme.marketing.referral.application.reward.ReferralRewardProjectionPort;
import com.acme.marketing.referral.application.qualification.ReferralQualificationPermitPort.Binding;
import com.acme.marketing.referral.application.qualification.ReferralQualificationRepository.Qualification;
import com.acme.marketing.referral.domain.qualification.ReferralProgressTransition.Progress;
import com.acme.marketing.referral.infrastructure.persistence.mapper.ReferralRewardMapper;
import com.acme.marketing.referral.infrastructure.persistence.mapper.ReferralRewardMapper.RewardRow;
import java.security.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/**
 * 资格事务中的永久奖励账本；先固定身份再等待配额/授权，任何内部失败均回滚资格。
 * 技术PENDING只暂停授权；明确失效和跌档永久取消原身份，恢复人数不重新发同一奖。
 */
@Repository
public class MybatisReferralRewardProjection implements ReferralRewardProjectionPort {
    private static final DateTimeFormatter SQL=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS").withZone(ZoneOffset.UTC);
    private final com.acme.marketing.referral.application.quota.ReferralQuotaService quotas;
    private final ReferralRewardMapper mapper;
    private final ObjectMapper json;
    /** 只依赖本库Mapper和规范JSON，不装配真实风控/权益调用。 */
    public MybatisReferralRewardProjection(ReferralRewardMapper mapper,ObjectMapper json,com.acme.marketing.referral.application.quota.ReferralQuotaService quotas){this.quotas=quotas;this.mapper=mapper;this.json=json;}

    /** 永久奖励、事件与审计加入当前事务；不获取其他参与者或反向获取配额总账户。 */
    @Override public void reconcile(Binding binding,ReferralPlan plan,Qualification q,Progress progress,String actor,String trace,Instant now){
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("reward projection requires qualification transaction");
        var p=binding.participant();var relation=binding.relation();
        require(q.tenantId().equals(p.tenantId()) && q.participantId().equals(p.participantId()) && q.relationId().equals(relation.relationId())
                && progress.tenantId().equals(p.tenantId()) && progress.participantId().equals(p.participantId()));
        var values=new HashMap<String,Object>();values.put("tenant",p.tenantId());values.put("participant",p.participantId());values.put("relation",relation.relationId());
        values.put("validCount",progress.validCount());values.put("q",q);values.put("progress",progress);values.put("now",SQL.format(now));values.put("actor",actor);values.put("trace",trace);
        if(q.state().equals("INELIGIBLE") || q.state().equals("REVIEW")){
            for(RewardRow reward:mapper.invalidatable(values)){
                require(reward.participantId().equals(p.participantId()) && reward.policyHash().equals(p.policyHash()));
                values.put("reward",reward.rewardId());values.put("previous",reward.revision());values.put("revision",Math.incrementExact(reward.revision()));
                one(mapper.invalidate(values));event(values,"REWARD_INVALIDATED",q.reason());
                quotas.releaseUnsubmitted(p.tenantId(),reward.rewardId(),actor,trace);
            }
        }
        if(!q.counted() || plan==null)return;
        ReferralPolicyValidator.validate(plan);
        var subject=Objects.requireNonNull(mapper.subject(values));require(subject.keyVersion()==binding.keyVersion());
        for(var rule:plan.rewards().stream().sorted(Comparator.comparing(ReferralRewardRule::ruleId)).toList()){
            if(rule.mode()==ReferralRewardRule.Mode.MILESTONE && progress.validCount()<rule.threshold())continue;
            boolean inviter=rule.role()==ReferralRewardRule.Role.INVITER;
            String beneficiary=inviter?subject.subjectKey():binding.subjectKey();
            String milestone=rule.mode()==ReferralRewardRule.Mode.PER_RELATION?"relation:"+relation.relationId():"threshold:"+rule.threshold();
            String reward=identity(p.tenantId(),p.campaignId(),beneficiary,rule.role().name(),rule.ruleId(),milestone);
            values.put("reward",reward);values.put("ruleJson",json.writeValueAsString(rule));
            RewardRow existing=mapper.reward(values);
            if(existing!=null){
                require(existing.participantId().equals(p.participantId()) && existing.policyHash().equals(p.policyHash()) && json.readValue(existing.ruleJson(),ReferralRewardRule.class).equals(rule));
                if(existing.entitlementState().equals("ELIGIBLE")){values.put("previous",existing.revision());one(mapper.refresh(values));}
                continue;
            }
            values.put("p",p);values.put("r",relation);values.put("rule",rule);values.put("milestone",milestone);values.put("beneficiary",beneficiary);
            values.put("source",ReferralAwardIdentity.sourceRequestId(p.tenantId(),reward));values.put("keyVersion",binding.keyVersion());
            // 密文保留原参与者/关系的AAD语义；外部签发必须通过可信保护适配器解密，不重新伪装为其他身份。
            values.put("cipherSource",inviter?"PARTICIPANT":"RELATION");values.put("cipherResource",inviter?p.participantId():relation.relationId());
            values.put("cipher",inviter?subject.cipher():readInviteeCipher(values));values.put("encryptionKey",inviter?subject.encryptionKeyId():values.get("inviteeEncryptionKey"));
            values.put("revision",1L);one(mapper.insert(values));event(values,"REWARD_ENTITLED","WAIT_QUOTA");
        }
    }
    private byte[] readInviteeCipher(Map<String,Object> values){
        // 同关系已由资格事务持锁；专用查询选择固定关系密文而非任意调用方字节。
        values.put("invitee",true);var subject=Objects.requireNonNull(mapper.subject(values));values.remove("invitee");
        require(subject.subjectKey().equals(values.get("beneficiary")) && subject.keyVersion()==((Long)values.get("keyVersion")));
        values.put("inviteeEncryptionKey",subject.encryptionKeyId());return subject.cipher();
    }
    private void event(Map<String,Object> values,String type,String reason){
        values.put("type",type);values.put("reason",reason);values.put("eventId",UUID.randomUUID().toString());values.put("auditId",UUID.randomUUID().toString());
        String payload=json.writeValueAsString(Map.of("schemaVersion",1,"rewardId",values.get("reward"),"rewardRevision",values.get("revision"),"eventType",type,"reason",reason));
        values.put("payload",payload);values.put("hash",Digests.sha256Hex(payload));one(mapper.outbox(values));one(mapper.audit(values));
    }
    private static String identity(String tenant,String campaign,String subject,String role,String rule,String milestone){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(CanonicalMapCodec.encode(Map.of("format","referral-reward/1","tenant",tenant,"campaign",campaign,"beneficiaryKey",subject,"role",role,"rule",rule,"milestone",milestone))));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static void one(int rows){require(rows==1);}
    private static void require(boolean ok){if(!ok)throw new IllegalStateException("reward ledger invariant failed");}
}
