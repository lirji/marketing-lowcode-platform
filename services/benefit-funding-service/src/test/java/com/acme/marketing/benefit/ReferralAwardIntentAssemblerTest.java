package com.acme.marketing.benefit;

import com.acme.marketing.benefit.application.*;
import com.acme.marketing.benefit.application.ReferralAwardProofPort.*;
import com.acme.marketing.benefit.infrastructure.ReferralAwardCandidateConfiguration;
import com.acme.marketing.contracts.referral.*;
import com.acme.marketing.platform.error.ConflictException;
import com.acme.marketing.platform.identity.*;
import com.acme.marketing.referral.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/** 真Ed25519签名与纯可信Port替身验证候选边界；不冒充真实目录/发布/资格确认。 */
class ReferralAwardIntentAssemblerTest {
    static final Instant NOW=Instant.parse("2026-09-08T00:00:00Z");
    static final String HASH="sha256:"+"a".repeat(64),REF="opaque/catalog/sku/revision-zero";

    @Test void realSignatureBuildsOnlyFrozenCouponAndPreservesExactBeneficiary() throws Exception {
        var f=new Fixture();var result=f.assemble();
        assertEquals("CANDIDATE_ONLY",result.status());assertEquals("marketing-referral",result.intent().sourceSystem());
        assertEquals(" 用户é ",result.intent().recipientRef());assertEquals(1,result.intent().items().size());
        var item=result.intent().items().getFirst();assertEquals("sku",item.benefitSkuId());assertEquals(0,item.expectedSkuVersion());
        assertEquals("COUPON",item.benefitType());assertEquals(1,item.quantity());assertNull(item.amountMinor());assertNull(item.currency());
        String body=f.json.writeValueAsString(result.intent());assertTrue(body.contains("\"expectedSkuVersion\":0"));
        assertFalse(result.toString().contains("用户"));assertFalse(result.intent().toString().contains("用户"));
        assertEquals(1,f.proofCalls);assertFalse(f.calledInTransaction);
    }

    @Test void resigningChangesNeitherSourceIdentityNorStableCandidate() throws Exception {
        var f=new Fixture();var original=f.assemble();
        f.claims=f.claims(ReferralAwardAuthorizationClaims.Role.INVITER,"relation",null,NOW.minusSeconds(1),NOW.plusSeconds(8));
        var replayCandidate=f.assemble();
        assertEquals(original.intent(),replayCandidate.intent());assertEquals(original.stableClaimsDigest(),replayCandidate.stableClaimsDigest());
        assertEquals(original.payloadHash(),replayCandidate.payloadHash());
        // 这里只证明重签的确定性，过期成功回放须后续永久受理账本实现。
    }

    @Test void invalidSignatureScopeSourceAndIssuerNeverReadProof() throws Exception {
        var f=new Fixture();String token=f.token();
        for(String candidate:List.of(token.substring(0,token.length()-3)+"AAA","malformed")) {
            assertThrows(ConflictException.class,()->f.service().assembleCandidate(f.scope,"org","shop",f.claims.sourceRequestId(),candidate));
        }
        assertThrows(ConflictException.class,()->f.service().assembleCandidate(f.scope,"org","shop","referral:wrong",token));
        var other=new TenantScope(new TenantId("other"),Set.of("org"),Set.of("shop"),"machine",Set.of("referral:award"));
        assertThrows(ConflictException.class,()->f.service().assembleCandidate(other,"org","shop",f.claims.sourceRequestId(),token));
        f.issuer="different-trusted-issuer";assertThrows(ConflictException.class,f::assemble);assertEquals(0,f.proofCalls);
    }

    @Test void opaqueCatalogMappingMustMatchBothOriginalReferencesAndCouponType() throws Exception {
        for(int variant=0;variant<7;variant++) {
            var f=new Fixture();int selected=variant;
            f.catalog=c->new Catalog(selected==0?"other":c.tenantId(),selected==1?"wrong":c.benefitReference(),selected==2?"wrong":c.skuReference(),
                    selected==3?"":c.benefitId(),selected==4?0:c.benefitVersion(),c.skuId(),selected==5?-1:c.skuVersion(),selected==6?"CASH":c.benefitType());
            assertThrows(ConflictException.class,f::assemble,"catalog variant "+variant);
        }
    }

    @Test void fullReleaseIdentityAbiAndStableSubjectDigestCannotBeSubstituted() throws Exception {
        for(int variant=0;variant<11;variant++) {
            var f=new Fixture();int v=variant;
            f.release=r->new Release(v==0?"other":r.tenantId(),v==1?"other":r.organizationId(),v==2?"other":r.shopId(),v==3?"other":r.campaignId(),
                    v==4?"other":r.definitionId(),v==5?2:r.definitionVersion(),v==6?2:r.generation(),v==7?"other":r.artifactId(),
                    v==8?"sha256:"+"b".repeat(64):r.artifactHash(),v==9?"wrong-abi":r.abi(),r.plan());
            if(v==10)f.digest="sha256:"+"f".repeat(64);
            assertThrows(ConflictException.class,f::assemble,"release variant "+variant);
        }
    }

    @Test void wholePlanRejectsDuplicateRuleRoleQuantityAndMilestoneMismatch() throws Exception {
        for(int variant=0;variant<4;variant++) {
            var f=new Fixture();
            var normal=f.rule();
            f.rules=switch(variant) {
                case 0->List.of(normal,normal);
                case 1->List.of(new ReferralRewardRule("rule",ReferralRewardRule.Role.INVITEE,normal.mode(),1,"benefit-opaque",REF,1,1,10));
                case 2->List.of(new ReferralRewardRule("rule",normal.role(),normal.mode(),1,"benefit-opaque",REF,2,1,10));
                default->List.of(new ReferralRewardRule("rule",normal.role(),ReferralRewardRule.Mode.MILESTONE,3,"benefit-opaque",REF,1,1,10));
            };
            assertThrows(ConflictException.class,f::assemble);
        }
        var milestone=new Fixture();milestone.rules=List.of(new ReferralRewardRule("rule",ReferralRewardRule.Role.INVITER,ReferralRewardRule.Mode.MILESTONE,3,"benefit-opaque",REF,1,1,10));
        milestone.claims=milestone.claims(ReferralAwardAuthorizationClaims.Role.INVITER,null,3L,NOW,NOW.plusSeconds(9));
        assertEquals("CANDIDATE_ONLY",milestone.assemble().status());
        milestone.claims=milestone.claims(ReferralAwardAuthorizationClaims.Role.INVITER,null,4L,NOW,NOW.plusSeconds(9));
        assertThrows(ConflictException.class,milestone::assemble);
    }

    @Test void proofReadsCannotReopenNanosecondTokenOrProofExpiry() throws Exception {
        for(boolean tokenDeadline:new boolean[]{true,false}) {
            var f=new Fixture();
            if(tokenDeadline)f.claims=f.claims(ReferralAwardAuthorizationClaims.Role.INVITER,"relation",null,NOW,NOW.plusNanos(500));
            else f.proofExpiry=NOW.plusNanos(500);
            f.afterProof=NOW.plusNanos(600);assertThrows(ConflictException.class,f::assemble);
        }
        var future=new Fixture();future.proofIssued=NOW.plusSeconds(1);assertThrows(ConflictException.class,future::assemble);
        var tooLong=new Fixture();tooLong.proofExpiry=NOW.plusSeconds(11);assertThrows(ConflictException.class,tooLong::assemble);
    }

    @Test void defaultPortsAndNonCenterModesAndOuterTransactionsFailClosed() throws Exception {
        var config=new ReferralAwardCandidateConfiguration();assertNull(config.referralAwardTrustPort().forTenant("tenant"));
        assertNull(config.referralAwardProofPort().resolve(null));
        for(String mode:List.of("LEGACY","SHADOW")) {
            var f=new Fixture();f.modes=new AwardDispatchModeRouter(mode,"");assertThrows(ConflictException.class,f::assemble);assertEquals(0,f.proofCalls);
        }
        var f=new Fixture();TransactionSynchronizationManager.setActualTransactionActive(true);
        try {assertThrows(ConflictException.class,f::assemble);}finally{TransactionSynchronizationManager.setActualTransactionActive(false);}
        assertEquals(0,f.proofCalls);
        var unavailable=new Fixture();unavailable.throwProof=true;
        var error=assertThrows(ConflictException.class,unavailable::assemble);assertNull(error.getCause());assertFalse(error.toString().contains("private-host"));
    }

    @Test void oldDroolsDtoRemainsFreeOfNewVersionField() {
        var oldItem=new AwardIntentAssembler.AwardItemIntent("item","sku",AwardIntentAssembler.BenefitType.COUPON,null,null,1,Map.of());
        var old=new AwardIntentAssembler.AwardIntent("1.0","drools-activity","old-request",null,"subject",null,
                AwardIntentAssembler.PartialPolicy.BEST_EFFORT,List.of(oldItem),Map.of());
        String payload=new ObjectMapper().writeValueAsString(old);
        assertFalse(payload.contains("expectedSkuVersion"));assertTrue(payload.contains("\"sourceSystem\":\"drools-activity\""));
    }

    static class Fixture {
        final KeyPair keys;final ObjectMapper json=new ObjectMapper();final MutableClock clock=new MutableClock();
        final TenantScope scope=new TenantScope(new TenantId("tenant"),Set.of("org"),Set.of("shop"),"machine",Set.of("referral:award"));
        ReferralAwardAuthorizationClaims claims;
        AwardDispatchModeRouter modes=new AwardDispatchModeRouter("LEGACY","tenant=CENTER,other=CENTER");
        String issuer="fixture-issuer",digest;int proofCalls;boolean calledInTransaction,throwProof;
        Instant proofIssued=NOW,proofExpiry=NOW.plusSeconds(10),afterProof=NOW;
        UnaryOperator<Catalog> catalog=UnaryOperator.identity();UnaryOperator<Release> release=UnaryOperator.identity();
        List<ReferralRewardRule> rules;
        Fixture() throws Exception {keys=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();claims=claims(ReferralAwardAuthorizationClaims.Role.INVITER,"relation",null,NOW,NOW.plusSeconds(9));rules=List.of(rule());}
        ReferralRewardRule rule(){return new ReferralRewardRule("rule",ReferralRewardRule.Role.INVITER,ReferralRewardRule.Mode.PER_RELATION,1,"benefit-opaque",REF,1,1,10);}
        ReferralAwardAuthorizationClaims claims(ReferralAwardAuthorizationClaims.Role role,String relation,Long milestone,Instant issued,Instant expires) {
            return new ReferralAwardAuthorizationClaims("fixture-issuer",ReferralAwardAuthorizationCodec.AUDIENCE,"tenant","org","shop","reward",
                    ReferralAwardIdentity.sourceRequestId("tenant","reward"),"campaign","definition",1,1,"artifact",HASH,"participant",relation,milestone," 用户é ",role,"rule",1,1,issued,expires);
        }
        String token(){return ReferralAwardAuthorizationCodec.encode("fixture-key",keys.getPrivate(),claims);}
        ReferralAwardIntentAssembler service(){return new ReferralAwardIntentAssembler(t->{calledInTransaction|=TransactionSynchronizationManager.isActualTransactionActive();return new ReferralAwardTrustPort.Trust(issuer,Map.of("fixture-key",keys.getPublic()),Duration.ofSeconds(10),Duration.ofSeconds(10));},v->{
            proofCalls++;calledInTransaction|=TransactionSynchronizationManager.isActualTransactionActive();if(throwProof)throw new IllegalStateException("private-host secret-subject");
            var plan=new ReferralPlan(NOW.minusSeconds(10),NOW.plusSeconds(100),NOW.plusSeconds(200),30,0,0,ReferralPlan.GoalType.REGISTERED_NEW_CUSTOMER,0,"CNY",rules);
            var r=release.apply(new Release("tenant","org","shop","campaign","definition",1,1,"artifact",HASH,"marketing-referral-plan/1",plan));
            var c=catalog.apply(new Catalog("tenant","benefit-opaque",REF,"benefit",1,"sku",0,"COUPON"));
            clock.now=afterProof;return new Proof(r,c,digest==null?v.stableDigest():digest,proofIssued,proofExpiry);
        },modes,clock,json);}
        ReferralAwardIntentAssembler.Candidate assemble(){return service().assembleCandidate(scope,"org","shop",claims.sourceRequestId(),token());}
    }
    static class MutableClock extends Clock {
        Instant now=NOW;@Override public Instant instant(){return now;}@Override public ZoneId getZone(){return ZoneOffset.UTC;}
        @Override public Clock withZone(ZoneId zone){return this;}
    }
}
