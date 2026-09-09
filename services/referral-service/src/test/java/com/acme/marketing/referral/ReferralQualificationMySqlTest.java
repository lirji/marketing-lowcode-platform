package com.acme.marketing.referral;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.marketing.platform.error.ConflictException;
import com.acme.marketing.platform.identity.*;
import com.acme.marketing.referral.application.*;
import com.acme.marketing.referral.application.evidence.*;
import com.acme.marketing.referral.application.evidence.ReferralEvidencePreparation.OrderKey;
import com.acme.marketing.referral.application.fanout.*;
import com.acme.marketing.referral.application.qualification.*;
import com.acme.marketing.referral.application.qualification.ReferralQualificationPermitPort.*;
import com.acme.marketing.referral.application.qualification.ReferralQualificationRepository.*;
import com.acme.marketing.referral.domain.ReferralRelation;
import com.acme.marketing.referral.application.fulfillment.*;
import com.acme.marketing.referral.application.fulfillment.ReferralFulfillmentProofPort.*;
import java.lang.reflect.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;

/** V1–V10真实MySQL联合验证；复用已验首绑/密文fixture，生产不注入测试身份或许可。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"marketing.security.mode=DEV","marketing.security.dev-headers-enabled=false","marketing.referral.subject-key-version=1","marketing.referral.invite-replay-seconds=3600"})
@Import({ReferralParticipationIntegrationTest.Fixtures.class,ReferralInviteTokenIntegrationTest.TokenFixtures.class,ReferralBindingIntegrationTest.BindingFixtures.class})
class ReferralQualificationMySqlTest {
    static final Instant NOW=ReferralParticipationIntegrationTest.NOW;
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){ReferralParticipationIntegrationTest.database(r);}
    @Autowired AutowireCapableBeanFactory beans;
    @Autowired ReferralQualificationRepository repository;
    @Autowired com.acme.marketing.referral.application.reevaluation.ReferralReevaluationService reevaluations;
    @Autowired com.acme.marketing.referral.infrastructure.persistence.mapper.ReferralReevaluationMapper reevaluationMapper;
    @Autowired com.acme.marketing.referral.infrastructure.persistence.mapper.ReferralFulfillmentMapper fulfillmentMapper;
    @Autowired ReferralFanoutRepository fanouts;
    @Autowired com.acme.marketing.referral.application.query.ReferralOperationsService operations;
    @org.springframework.boot.test.web.server.LocalServerPort int port;
    @Autowired com.acme.marketing.referral.infrastructure.persistence.mapper.ReferralAuthorizationMapper authorizationMapper;
    @Autowired com.acme.marketing.referral.application.quota.ReferralQuotaService quotas;
    @Autowired com.acme.marketing.referral.infrastructure.persistence.mapper.ReferralQuotaMapper quotaMapper;
    @Autowired com.acme.marketing.referral.infrastructure.persistence.mapper.ReferralRewardMapper rewardMapper;
    @Autowired com.acme.marketing.referral.application.reward.ReferralRewardProjectionPort rewards;
    @Autowired PlatformTransactionManager manager;
    @Autowired JdbcTemplate jdbc;
    ReferralBindingIntegrationTest binding;
    ReferralEvidenceMySqlTest facts;
    ReferralRelation relation;
    ReferralQualificationService service;
    ReferralEvidenceFanoutService fanout;
    long observation,memberRevision;
    String memberDigest;
    boolean authority,registered;
    List<ReferralRewardRule> rules;
    Risk risk;
    Runnable permitHook;

    @BeforeEach void setup(){
        binding=new ReferralBindingIntegrationTest();beans.autowireBean(binding);binding.prepare();
        facts=new ReferralEvidenceMySqlTest();beans.autowireBean(facts);facts.tenant=binding.tenant;
        facts.protection=new ReferralEvidenceMySqlTest.Crypto(facts.json){
            @Override String subjectKey(ReferralOrderEvidence.Scope scope){
                var request=new TrustedReferralSubjectPort.RequestBinding(binding.tenant,"campaign","org","shop","JOIN_V1","fixture-key","a".repeat(64),"INTERNAL","referral.participation.join");
                return binding.subjects.resolve(binding.scope(),scope.canonicalSubject(),request).subjectKey();
            }
        };
        facts.sources=facts.new Source();
        relation=bind("subject-B");registered=false;rules=List.of(new ReferralRewardRule("reward",ReferralRewardRule.Role.INVITER,ReferralRewardRule.Mode.PER_RELATION,1,"benefit-v1","sku-v1",1,1,100));observation=0;memberRevision=1;memberDigest="f".repeat(64);authority=true;risk=Risk.ALLOW;permitHook=()->{};
        service=service(repository);fanout=new ReferralEvidenceFanoutService(fanouts,repository,binding.clock,manager,30,1);
    }
    TenantScope scope(){return new TenantScope(new TenantId(binding.tenant),Set.of("org"),Set.of("shop"),"qualification-machine",Set.of("referral:evaluate","referral:project"));}
    ReferralRelation bind(String subject){return binding.service.bind(binding.scope(),binding.request(subject,"bind-"+subject));}
    ReferralPlan plan(){return new ReferralPlan(NOW.minusSeconds(600),NOW.plusSeconds(600),NOW.plusSeconds(3600),600,observation,60,registered?ReferralPlan.GoalType.REGISTERED_NEW_CUSTOMER:ReferralPlan.GoalType.FIRST_ORDER_SETTLED,50,"CNY",rules);}
    ReferralQualificationService service(ReferralQualificationRepository repo){
        ReferralQualificationPermitPort permits=(scope,b,cipher,key)->{
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());permitHook.run();
            Instant now=binding.clock.instant();
            return authority?new Permit(b,plan(),ReferralPolicyEvaluator.Fact.YES,memberRevision,"member-v1",b.relation().boundAt(),registered?b.relation().boundAt().minusSeconds(1):null,registered?b.relation().boundAt():null,ReferralPolicyEvaluator.Fact.YES,
                    registered?null:new OrderKey(binding.tenant,"channel","order"),registered?null:"policy-v1",risk,"proof",now,now.plusSeconds(10),memberDigest,registered?"1".repeat(64):null):null;
        };
        return new ReferralQualificationService(repo,binding.invites,binding.participants,facts.repository,facts.protection,permits,binding.clock,manager,rewards,1,30,5,20);
    }
    ReferralOrderEvidence.Snapshot snapshot(long revision,long refund){return new ReferralOrderEvidence.Snapshot(new ReferralOrderEvidence.Scope(binding.tenant,"channel","subject-B","order","org","shop"),revision,"policy-v1",ReferralPolicyEvaluator.Fact.YES,true,ReferralOrderEvidence.OrderState.SETTLED,NOW.plusSeconds(1).plusNanos(700),100,refund,0,100-refund,"CNY");}
    void evidence(long revision,long refund){binding.clock.now.set(NOW.plusSeconds(2));var s=snapshot(revision,refund);facts.service().accept(facts.scope(),facts.sources.register("event-"+UUID.randomUUID(),s,binding.clock.instant().minusNanos(1)));}
    Qualification run(){return service.process(scope(),relation.relationId(),"worker","trace");}
    void signal(){new TransactionTemplate(manager).executeWithoutResult(tx->repository.enqueue(new ReferralProjectionEnqueuePort.Signal(binding.tenant,relation.participantId(),relation.relationId(),null,0,"BOUND")));}
    Task task(){return new TransactionTemplate(manager).execute(tx->repository.lockTask(binding.tenant,relation.relationId()));}
    void progress(long valid,long ever){var p=new TransactionTemplate(manager).execute(tx->repository.progress(binding.tenant,relation.participantId(),binding.clock.instant()));assertNotNull(p);assertEquals(valid,p.validCount());assertEquals(ever,p.everQualifiedCount());}
    int count(String table){return binding.count(table);}

    @Test void bindingEnqueuesAndEvidenceFanoutThenProjectsExactlyOnce(){
        assertEquals(1,task().requestedRevision());evidence(1,0);
        var page=fanout.runOne(scope(),"fanout").orElseThrow();assertEquals(1,page.enqueued());assertFalse(page.more());assertEquals(2,task().requestedRevision());
        Qualification q=run();assertTrue(q.counted());assertEquals(q,repository.qualification(binding.tenant,relation.relationId(),false));progress(1,1);
        assertEquals("DONE",task().status());assertNull(run());assertTrue(fanout.runOne(scope(),"fanout").isEmpty());
        signal();assertTrue(run().counted());progress(1,1);
    }
    @Test void refundRevokesCurrentAndAuthorityRecoveryNeverRecountsHistoricalPerson(){
        evidence(1,0);assertTrue(run().counted());progress(1,1);
        authority=false;signal();assertEquals("PENDING",run().state());progress(0,1);
        authority=true;signal();assertTrue(run().counted());progress(1,1);
        evidence(2,80);fanout.runOne(scope(),"fanout").orElseThrow();assertFalse(run().counted());progress(0,1);
    }
    @Test void evidenceBeforeBindingIsRecoveredByBoundTaskAfterFanoutFinished(){
        binding.clock.now.set(NOW.plusNanos(900));
        var original=snapshot(1,0);
        var early=new ReferralOrderEvidence.Snapshot(new ReferralOrderEvidence.Scope(binding.tenant,"channel","subject-C","order","org","shop"),1,original.firstOrderPolicyVersion(),original.firstEligibleOrder(),true,original.orderState(),NOW.plusNanos(700),100,0,0,100,"CNY");
        facts.service().accept(facts.scope(),facts.sources.register("before-bind",early,binding.clock.instant().minusNanos(1)));
        assertEquals(0,fanout.runOne(scope(),"fanout").orElseThrow().enqueued());
        // 微秒内事件先提交，绑定的持久时间仍为NOW；由BOUND补回已结束扫描漏掉的关系。
        relation=bind("subject-C");assertEquals(NOW,relation.boundAt());
        assertTrue(run().counted());progress(1,1);
    }
    @Test void exactObservationDeadlineSurvivesDatabaseRoundTrip(){
        observation=10;evidence(1,0);var pending=run();Instant due=NOW.plusSeconds(11).plusNanos(700);
        assertEquals(due,pending.dueAt());assertEquals(due,task().availableAt());progress(0,0);
        binding.clock.now.set(due.minusNanos(1));assertNull(run());binding.clock.now.set(due);assertTrue(run().counted());progress(1,1);
    }
    @Test void oldLeaseCannotEraseNewRequestArrivingDuringAuthorityPreparation(){
        evidence(1,0);permitHook=()->{permitHook=()->{};signal();};
        assertEquals("REFERRAL_QUALIFICATION_LEASE_LOST",assertThrows(ConflictException.class,this::run).code());
        assertEquals(0,count("mk_referral_qualification"));assertEquals("PENDING",task().status());assertEquals(2,task().requestedRevision());
        assertTrue(run().counted());progress(1,1);
    }
    @Test void failureAfterAllProjectionSqlRollsBackCountsEventsAndCompletion(){
        evidence(1,0);int events=count("mk_referral_outbox"),audits=count("mk_referral_audit");
        var failing=proxy((method,args)->{Object result=invoke(method,args);if(method.getName().equals("save"))throw new IllegalStateException("fixture after save");return result;});
        assertThrows(IllegalStateException.class,()->service(failing).process(scope(),relation.relationId(),"worker","trace"));
        assertEquals(0,count("mk_referral_qualification"));assertEquals(0,count("mk_referral_progress"));assertEquals(events,count("mk_referral_outbox"));assertEquals(audits,count("mk_referral_audit"));
        assertEquals("PROCESSING",task().status());binding.clock.now.set(NOW.plusSeconds(33));assertTrue(run().counted());progress(1,1);
    }
    @Test void expiryAfterSaveRollsBackEveryProjectionWrite(){
        evidence(1,0);var expired=proxy((method,args)->{Object result=invoke(method,args);if(method.getName().equals("save"))binding.clock.now.set(NOW.plusSeconds(12));return result;});
        assertEquals("REFERRAL_QUALIFICATION_EXPIRED_BEFORE_COMMIT",assertThrows(ConflictException.class,()->service(expired).process(scope(),relation.relationId(),"worker","trace")).code());
        assertEquals(0,count("mk_referral_qualification"));assertEquals(0,count("mk_referral_progress"));assertEquals("PROCESSING",task().status());
    }
    @Test void sameMemberRevisionConflictRemainsQuarantinedAfterLaterGoodRevision(){
        evidence(1,0);assertTrue(run().counted());memberDigest="0".repeat(64);signal();assertTrue(run().memberQuarantined());progress(0,1);
        memberRevision=2;memberDigest="f".repeat(64);signal();assertEquals("REVIEW",run().state());progress(0,1);
    }
    @Test void oldMemberRevisionDoesNotReplaceHighestKnownWatermark(){
        memberRevision=3;evidence(1,0);assertTrue(run().counted());memberRevision=2;signal();var q=run();assertEquals("MEMBER_REVISION_STALE",q.reason());assertEquals(3,q.memberRevision());progress(0,1);
    }
    @Test void concurrentWorkersOnlyOneCanClaimTheRelation() throws Exception {
        evidence(1,0);try(var pool=Executors.newFixedThreadPool(2)){CountDownLatch start=new CountDownLatch(1);Callable<Qualification> work=()->{start.await();return run();};var a=pool.submit(work);var b=pool.submit(work);start.countDown();var results=Arrays.asList(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));assertEquals(1,results.stream().filter(Objects::nonNull).count());}
        progress(1,1);assertEquals(1,count("mk_referral_qualification"));
    }
    @Test void fanoutPagesAcrossCampaignsWithoutDuplicateOrSkippedRelations(){
        var other=binding.joins.join(binding.scope(),new ReferralParticipationService.Join("campaign-2","org","shop","subject-A","join-other","trace"));
        var token=binding.issue(other,"subject-A","token-other");
        var second=binding.service.bind(binding.scope(),binding.bind(token.inviteToken(),"subject-B","bind-other"));
        evidence(1,0);var first=fanout.runOne(scope(),"one").orElseThrow();assertEquals(1,first.enqueued());assertTrue(first.more());
        var last=fanout.runOne(scope(),"two").orElseThrow();assertEquals(1,last.enqueued());assertFalse(last.more());assertTrue(fanout.runOne(scope(),"three").isEmpty());
        assertEquals(2,task().requestedRevision());
        assertEquals(2,new TransactionTemplate(manager).execute(tx->repository.lockTask(binding.tenant,second.relationId())).requestedRevision());
        assertTrue(run().counted());assertTrue(service.process(scope(),second.relationId(),"worker","trace").counted());
        assertEquals(2,count("mk_referral_qualification"));assertEquals(2,count("mk_referral_progress"));
    }
    @Test void newerEvidenceInvalidatesOldFanoutClaim(){
        evidence(1,0);var old=fanout.claim(scope(),"old").orElseThrow();evidence(2,80);
        assertThrows(ConflictException.class,()->fanout.dispatchPage(scope(),old));assertEquals(1,task().requestedRevision());
        assertEquals(1,fanout.runOne(scope(),"new").orElseThrow().enqueued());assertFalse(run().counted());progress(0,0);
    }
    @Test void fanoutQueueFailureRollsBackAllSignalsAndCursor(){
        evidence(1,0);var failing=proxy((method,args)->{Object result=invoke(method,args);if(method.getName().equals("enqueue"))throw new IllegalStateException("fixture after enqueue");return result;});
        var dispatcher=new ReferralEvidenceFanoutService(fanouts,failing,binding.clock,manager,30,1);var claim=dispatcher.claim(scope(),"fanout").orElseThrow();
        assertThrows(IllegalStateException.class,()->dispatcher.dispatchPage(scope(),claim));assertEquals(1,task().requestedRevision());
        assertEquals(1,fanout.dispatchPage(scope(),claim).enqueued());assertEquals(2,task().requestedRevision());
    }
    @Test void tenantAndOrganizationPermissionsCannotReadOrCompleteAnotherTask(){
        evidence(1,0);var foreign=new TenantScope(new TenantId("foreign"),Set.of("org"),Set.of("shop"),"machine",Set.of("referral:evaluate"));assertNull(service.process(foreign,relation.relationId(),"worker","trace"));
        var wrongOrg=new TenantScope(new TenantId(binding.tenant),Set.of("other"),Set.of("shop"),"machine",Set.of("referral:evaluate"));assertThrows(RuntimeException.class,()->service.process(wrongOrg,relation.relationId(),"worker","trace"));assertEquals("PENDING",task().status());
    }
    void bilateralRules(){registered=true;rules=List.of(
        new ReferralRewardRule("inviter",ReferralRewardRule.Role.INVITER,ReferralRewardRule.Mode.PER_RELATION,1,"benefit-v1","sku-v1",1,10,100),
        new ReferralRewardRule("invitee",ReferralRewardRule.Role.INVITEE,ReferralRewardRule.Mode.PER_RELATION,1,"benefit-v1","sku-v1",1,1,100),
        new ReferralRewardRule("tier-one",ReferralRewardRule.Role.INVITER,ReferralRewardRule.Mode.MILESTONE,1,"benefit-v1","sku-v1",1,1,100),
        new ReferralRewardRule("tier-two",ReferralRewardRule.Role.INVITER,ReferralRewardRule.Mode.MILESTONE,2,"benefit-v1","sku-v1",1,1,100));}
    @Test void bilateralAndMilestoneRewardsPersistOnceAndNeverReissueAfterInvalidation(){
        bilateralRules();assertTrue(run().counted());var second=bind("subject-C");assertTrue(service.process(scope(),second.relationId(),"worker","trace").counted());progress(2,2);
        assertEquals(6,count("mk_referral_reward"));signal();assertTrue(run().counted());assertEquals(6,count("mk_referral_reward"));
        risk=Risk.REJECT;signal();assertFalse(run().counted());progress(1,2);
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM mk_referral_reward WHERE tenant_id=? AND entitlement_state='INVALIDATED'",Integer.class,binding.tenant));
        risk=Risk.ALLOW;signal();assertTrue(run().counted());progress(2,2);assertEquals(6,count("mk_referral_reward"));
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM mk_referral_reward WHERE tenant_id=? AND entitlement_state='INVALIDATED'",Integer.class,binding.tenant));
        assertEquals(6,jdbc.queryForObject("SELECT COUNT(DISTINCT source_request_id) FROM mk_referral_reward WHERE tenant_id=?",Integer.class,binding.tenant));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mk_referral_reward WHERE tenant_id=? AND (authorization_state<>'NONE' OR delivery_state<>'NOT_SUBMITTED')",Integer.class,binding.tenant));
    }
    @Test void transientAuthorityFailurePausesQualificationWithoutPermanentlyInvalidatingRewards(){
        bilateralRules();assertTrue(run().counted());authority=false;signal();assertEquals("PENDING",run().state());progress(0,1);
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mk_referral_reward WHERE tenant_id=? AND entitlement_state='INVALIDATED'",Integer.class,binding.tenant));
        authority=true;signal();assertTrue(run().counted());assertEquals(3,count("mk_referral_reward"));progress(1,1);
    }
    @Test void differentFriendsConcurrentlyCountAndCreateAllCrossedMilestones() throws Exception {
        bilateralRules();var second=bind("subject-C");var third=bind("subject-D");
        try(var pool=Executors.newFixedThreadPool(3)){var start=new CountDownLatch(1);var results=new ArrayList<Future<Qualification>>();
            for(var r:List.of(relation,second,third))results.add(pool.submit(()->{start.await();return service.process(scope(),r.relationId(),"worker-"+r.relationId(),"trace");}));
            start.countDown();for(var result:results)assertTrue(result.get(15,TimeUnit.SECONDS).counted());}
        progress(3,3);assertEquals(8,count("mk_referral_reward"));
    }
    TenantScope quotaScope(){return new TenantScope(new TenantId(binding.tenant),Set.of("org"),Set.of("shop"),"quota-machine",Set.of("referral:quota-configure","referral:quota"));}
    String rewardFor(ReferralRelation r){return jdbc.queryForObject("SELECT reward_id FROM mk_referral_reward WHERE tenant_id=? AND relation_id=?",String.class,binding.tenant,r.relationId());}
    @Test void quotaInitializationAndReservationArePermanentAndIdempotent(){
        registered=true;assertTrue(run().counted());String reward=rewardFor(relation);
        assertEquals("WAIT_QUOTA",quotas.reserve(quotaScope(),reward));quotas.initialize(quotaScope(),reward,1);
        assertEquals("RESERVED",quotas.reserve(quotaScope(),reward));assertEquals("RESERVED",quotas.reserve(quotaScope(),reward));quotas.initialize(quotaScope(),reward,1);
        assertEquals(1,count("mk_referral_quota_reservation"));assertEquals(1L,jdbc.queryForObject("SELECT reserved FROM mk_referral_quota_bucket WHERE tenant_id=?",Long.class,binding.tenant));
        assertEquals(99L,jdbc.queryForObject("SELECT available FROM mk_referral_quota_bucket WHERE tenant_id=?",Long.class,binding.tenant));
        assertThrows(ConflictException.class,()->quotas.initialize(quotaScope(),reward,2));
    }
    @Test void personalLimitCannotBeBypassedByDifferentRewardOrFriend(){
        registered=true;run();var second=bind("subject-C");service.process(scope(),second.relationId(),"worker","trace");String a=rewardFor(relation),b=rewardFor(second);
        quotas.initialize(quotaScope(),a,2);assertEquals("RESERVED",quotas.reserve(quotaScope(),a));assertEquals("WAIT_SUBJECT_LIMIT",quotas.reserve(quotaScope(),b));assertEquals(1,count("mk_referral_quota_reservation"));
    }
    @Test void differentInvitersCompeteForLastCampaignQuotaWithoutOverReservation() throws Exception {
        registered=true;rules=List.of(new ReferralRewardRule("reward",ReferralRewardRule.Role.INVITER,ReferralRewardRule.Mode.PER_RELATION,1,"benefit-v1","sku-v1",1,1,1));run();
        var other=binding.join("subject-D","join-key-D");var token=binding.issue(other,"subject-D","issue-key-D");var second=binding.service.bind(binding.scope(),binding.bind(token.inviteToken(),"subject-E","bind-key-E"));
        service.process(scope(),second.relationId(),"worker","trace");String a=rewardFor(relation),b=rewardFor(second);quotas.initialize(quotaScope(),a,1);
        try(var pool=Executors.newFixedThreadPool(2)){var start=new CountDownLatch(1);var x=pool.submit(()->{start.await();return quotas.reserve(quotaScope(),a);});var y=pool.submit(()->{start.await();return quotas.reserve(quotaScope(),b);});start.countDown();
            assertEquals(Set.of("RESERVED","WAIT_QUOTA"),Set.of(x.get(15,TimeUnit.SECONDS),y.get(15,TimeUnit.SECONDS)));}
        assertEquals(1,count("mk_referral_quota_reservation"));assertEquals(1L,jdbc.queryForObject("SELECT reserved FROM mk_referral_quota_bucket WHERE tenant_id=?",Long.class,binding.tenant));
    }
    @Test void transferMovesOnlyFreeCapacityAndAdvancesBothEpochs(){
        registered=true;run();String reward=rewardFor(relation);quotas.initialize(quotaScope(),reward,2);quotas.transfer(quotaScope(),reward,0,1,10);
        assertEquals(100L,jdbc.queryForObject("SELECT SUM(allocated) FROM mk_referral_quota_bucket WHERE tenant_id=?",Long.class,binding.tenant));
        assertEquals(2L,jdbc.queryForObject("SELECT MIN(fencing_epoch) FROM mk_referral_quota_bucket WHERE tenant_id=?",Long.class,binding.tenant));
        assertEquals(40L,jdbc.queryForObject("SELECT available FROM mk_referral_quota_bucket WHERE tenant_id=? AND bucket_id=0",Long.class,binding.tenant));
        assertThrows(IllegalStateException.class,()->quotas.transfer(quotaScope(),reward,0,1,41));
        assertEquals("RESERVED",quotas.reserve(quotaScope(),reward));
    }
    @Test void quotaOutboxFailureRollsBackRewardBucketPersonalCountAndReservation(){
        registered=true;run();String reward=rewardFor(relation);quotas.initialize(quotaScope(),reward,1);int events=count("mk_referral_outbox");
        var failing=org.mockito.Mockito.mock(com.acme.marketing.referral.infrastructure.persistence.mapper.ReferralRewardMapper.class);
        org.mockito.Mockito.when(failing.outbox(org.mockito.ArgumentMatchers.any())).thenAnswer(call->{rewardMapper.outbox(call.getArgument(0));throw new IllegalStateException("fixture after quota outbox");});
        var service=new com.acme.marketing.referral.application.quota.ReferralQuotaService(quotaMapper,failing,binding.clock,facts.json,manager);
        assertThrows(IllegalStateException.class,()->service.reserve(quotaScope(),reward));assertEquals(0,count("mk_referral_quota_reservation"));assertEquals(0,count("mk_referral_subject_quota"));assertEquals(events,count("mk_referral_outbox"));
        assertEquals(0L,jdbc.queryForObject("SELECT reserved FROM mk_referral_quota_bucket WHERE tenant_id=?",Long.class,binding.tenant));assertEquals("RESERVED",quotas.reserve(quotaScope(),reward));
    }
    @Test void newEvidenceBeforeProjectionBlocksNewQuotaReservation(){
        evidence(1,0);run();String reward=rewardFor(relation);quotas.initialize(quotaScope(),reward,1);evidence(2,80);
        assertEquals("WAIT_AUTHORITY",quotas.reserve(quotaScope(),reward));assertEquals(0,count("mk_referral_quota_reservation"));
    }
    @Test void invalidationBeforeAuthorizationReleasesQuotaAtomicallyAndNeverReissues(){
        registered=true;run();String reward=rewardFor(relation);quotas.initialize(quotaScope(),reward,1);assertEquals("RESERVED",quotas.reserve(quotaScope(),reward));
        risk=Risk.REJECT;signal();run();assertEquals("RELEASED",quotas.reserve(quotaScope(),reward));
        assertEquals(100L,jdbc.queryForObject("SELECT available FROM mk_referral_quota_bucket WHERE tenant_id=?",Long.class,binding.tenant));
        assertEquals(0L,jdbc.queryForObject("SELECT reserved_count FROM mk_referral_subject_quota WHERE tenant_id=?",Long.class,binding.tenant));
        risk=Risk.ALLOW;signal();assertTrue(run().counted());assertEquals("RELEASED",quotas.reserve(quotaScope(),reward));assertEquals(1,count("mk_referral_reward"));assertEquals(1,count("mk_referral_quota_reservation"));
    }
    @Test void invalidationAfterPossibleExternalSubmissionRetainsQuotaForReconciliation(){
        registered=true;run();String reward=rewardFor(relation);quotas.initialize(quotaScope(),reward,1);quotas.reserve(quotaScope(),reward);
        // 只在隔离fixture模拟已确认但外部响应未知；不是实际授权/渠道发送验收。
        jdbc.update("UPDATE mk_referral_reward SET authorization_state='CONFIRMED',delivery_state='UNKNOWN' WHERE tenant_id=? AND reward_id=?",binding.tenant,reward);
        risk=Risk.REJECT;signal();run();assertEquals("RESERVED",quotas.reserve(quotaScope(),reward));
        assertEquals("PENDING",jdbc.queryForObject("SELECT compensation_state FROM mk_referral_reward WHERE tenant_id=?",String.class,binding.tenant));
        assertEquals(1L,jdbc.queryForObject("SELECT reserved FROM mk_referral_quota_bucket WHERE tenant_id=?",Long.class,binding.tenant));
    }
    @Test void failureAfterRewardProjectionAlsoRollsBackQuotaCancellation(){
        registered=true;run();String reward=rewardFor(relation);quotas.initialize(quotaScope(),reward,1);quotas.reserve(quotaScope(),reward);
        var real=rewards;rewards=(b,p,q,progress,actor,trace,now)->{real.reconcile(b,p,q,progress,actor,trace,now);throw new IllegalStateException("fixture after reward cancellation");};
        service=service(repository);risk=Risk.REJECT;signal();assertThrows(IllegalStateException.class,this::run);
        assertEquals("ELIGIBLE",jdbc.queryForObject("SELECT entitlement_state FROM mk_referral_reward WHERE tenant_id=?",String.class,binding.tenant));
        assertEquals("RESERVED",jdbc.queryForObject("SELECT state FROM mk_referral_quota_reservation WHERE tenant_id=?",String.class,binding.tenant));progress(1,1);
    }
    TenantScope authorizationScope(){return new TenantScope(new TenantId(binding.tenant),Set.of("org"),Set.of("shop"),"benefit-machine",Set.of("referral:confirm-authorization"));}
    com.acme.marketing.referral.application.authorization.ReferralAuthorizationService authorizer(Runnable hook){
        com.acme.marketing.referral.application.authorization.ReferralAuthorizationProofPort proof=(scope,request,reward)->{
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());hook.run();var now=binding.clock.instant();
            var window=new com.acme.marketing.referral.domain.authorization.ReferralRewardAuthorization.Window(now,now.plusSeconds(10));
            return new com.acme.marketing.referral.application.authorization.ReferralAuthorizationProofPort.Proof(request,reward,window,window,window);
        };
        return new com.acme.marketing.referral.application.authorization.ReferralAuthorizationService(authorizationMapper,rewardMapper,binding.participants,proof,binding.clock,facts.json,manager,100,20);
    }
    com.acme.marketing.referral.application.authorization.ReferralAuthorizationProofPort.Request authorizationRequest(String reward){
        return new com.acme.marketing.referral.application.authorization.ReferralAuthorizationProofPort.Request(reward,com.acme.marketing.contracts.referral.ReferralAwardIdentity.sourceRequestId(binding.tenant,reward),"sha256:"+"a".repeat(64),
            jdbc.queryForObject("SELECT qualification_revision FROM mk_referral_reward WHERE tenant_id=? AND reward_id=?",Long.class,binding.tenant,reward),"fixture-candidate","fixture-risk");
    }
    String reservedReward(){registered=true;run();String reward=rewardFor(relation);quotas.initialize(quotaScope(),reward,1);quotas.reserve(quotaScope(),reward);return reward;}
    @Test void authorizationReceiptReplaysForeverWithExactOriginalTime(){
        String reward=reservedReward();binding.clock.now.set(NOW.plusNanos(789));var request=authorizationRequest(reward);var first=authorizer(()->{}).confirm(authorizationScope(),request);
        assertEquals(NOW.plusNanos(789),first.confirmedAt());assertFalse(first.replay());assertFalse(first.dispatchAllowed());
        binding.clock.now.set(NOW.plusSeconds(10000));var replay=authorizer(()->{throw new AssertionError("must not reverify old candidate");}).confirm(authorizationScope(),request);
        assertEquals(first.confirmationId(),replay.confirmationId());assertEquals(first.confirmedAt(),replay.confirmedAt());assertTrue(replay.replay());assertEquals(1,count("mk_referral_authorization_receipt"));
    }
    @Test void cancellationBeforeAuthorizationPreventsFirstReceiptAndReleasesQuota(){
        String reward=reservedReward();var request=authorizationRequest(reward);risk=Risk.REJECT;signal();run();
        assertThrows(ConflictException.class,()->authorizer(()->{}).confirm(authorizationScope(),request));assertEquals(0,count("mk_referral_authorization_receipt"));
        assertEquals("RELEASED",quotas.reserve(quotaScope(),reward));
    }
    @Test void cancellationAfterAuthorizationPreservesReceiptAndPendingCompensation(){
        String reward=reservedReward();var request=authorizationRequest(reward);var first=authorizer(()->{}).confirm(authorizationScope(),request);risk=Risk.REJECT;signal();run();
        var replay=authorizer(()->{}).recover(authorizationScope(),reward,request.sourceRequestId(),request.stableClaimsDigest());
        assertEquals(first.confirmationId(),replay.confirmationId());assertEquals("CANCEL_REQUESTED",replay.currentState());assertTrue(replay.cancelRevision()>0);assertFalse(replay.dispatchAllowed());
        assertEquals("RESERVED",quotas.reserve(quotaScope(),reward));assertEquals("PENDING",jdbc.queryForObject("SELECT compensation_state FROM mk_referral_reward WHERE tenant_id=?",String.class,binding.tenant));
    }
    @Test void evidenceCommittedButNotProjectedCannotPassAuthorization(){
        evidence(1,0);run();String reward=rewardFor(relation);quotas.initialize(quotaScope(),reward,1);quotas.reserve(quotaScope(),reward);var request=authorizationRequest(reward);evidence(2,80);
        assertThrows(ConflictException.class,()->authorizer(()->{}).confirm(authorizationScope(),request));assertEquals(0,count("mk_referral_authorization_receipt"));
    }
    @Test void cancellationDuringOutsideProofCannotBeOverwrittenByOldAuthorization(){
        String reward=reservedReward();var request=authorizationRequest(reward);
        assertThrows(ConflictException.class,()->authorizer(()->{risk=Risk.REJECT;signal();run();}).confirm(authorizationScope(),request));
        assertEquals(0,count("mk_referral_authorization_receipt"));assertEquals("RELEASED",quotas.reserve(quotaScope(),reward));
    }
    @Test void concurrentConfirmationsPersistOnlyOnePermanentReceipt() throws Exception {
        String reward=reservedReward();var request=authorizationRequest(reward);
        try(var pool=Executors.newFixedThreadPool(2)){var start=new CountDownLatch(1);var x=pool.submit(()->{start.await();return authorizer(()->{}).confirm(authorizationScope(),request);});var y=pool.submit(()->{start.await();return authorizer(()->{}).confirm(authorizationScope(),request);});start.countDown();
            assertEquals(x.get(15,TimeUnit.SECONDS).confirmationId(),y.get(15,TimeUnit.SECONDS).confirmationId());}
        assertEquals(1,count("mk_referral_authorization_receipt"));
    }
    @Test void changedStableClaimsCannotReplayAnotherAuthorization(){
        String reward=reservedReward();var request=authorizationRequest(reward);authorizer(()->{}).confirm(authorizationScope(),request);
        assertThrows(ConflictException.class,()->authorizer(()->{}).recover(authorizationScope(),reward,request.sourceRequestId(),"sha256:"+"b".repeat(64)));
    }
    @Test void defaultUnavailableProofNeverCreatesAuthorization(){
        String reward=reservedReward();var proof=new com.acme.marketing.referral.infrastructure.ReferralAuthorizationConfiguration().referralAuthorizationProofs();
        var service=new com.acme.marketing.referral.application.authorization.ReferralAuthorizationService(authorizationMapper,rewardMapper,binding.participants,proof,binding.clock,facts.json,manager,100,20);
        assertThrows(ConflictException.class,()->service.confirm(authorizationScope(),authorizationRequest(reward)));assertEquals(0,count("mk_referral_authorization_receipt"));
    }
    @Test void authorizationExpiresDuringFinalSqlRollsBackReceiptAndReward(){
        String reward=reservedReward();var request=authorizationRequest(reward);
        var delayed=(com.acme.marketing.referral.infrastructure.persistence.mapper.ReferralAuthorizationMapper)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{com.acme.marketing.referral.infrastructure.persistence.mapper.ReferralAuthorizationMapper.class},(p,m,a)->{
            try{Object result=m.invoke(authorizationMapper,a);if(m.getName().equals("confirm"))binding.clock.now.set(NOW.plusSeconds(10));return result;}catch(InvocationTargetException failed){throw failed.getCause();}
        });
        com.acme.marketing.referral.application.authorization.ReferralAuthorizationProofPort proof=(scope,req,row)->{
            var window=new com.acme.marketing.referral.domain.authorization.ReferralRewardAuthorization.Window(NOW,NOW.plusSeconds(10));return new com.acme.marketing.referral.application.authorization.ReferralAuthorizationProofPort.Proof(req,row,window,window,window);};
        var service=new com.acme.marketing.referral.application.authorization.ReferralAuthorizationService(delayed,rewardMapper,binding.participants,proof,binding.clock,facts.json,manager,100,20);
        assertThrows(ConflictException.class,()->service.confirm(authorizationScope(),request));assertEquals(0,count("mk_referral_authorization_receipt"));
        assertEquals("NONE",jdbc.queryForObject("SELECT authorization_state FROM mk_referral_reward WHERE tenant_id=?",String.class,binding.tenant));
        assertEquals("RESERVED",quotas.reserve(quotaScope(),reward));
    }
    TenantScope readScope(){return new TenantScope(new TenantId(binding.tenant),Set.of("org"),Set.of("shop"),"operator",Set.of("referral:read"));}
    @Test void operationsPagesShowRealStatesAndNoSensitiveSubjects(){
        assertNull(operations.participants(readScope(),"campaign",null,20).items().getFirst().validCount());
        assertNull(operations.relations(readScope(),"campaign",null,20).items().getFirst().qualificationState());
        bilateralRules();run();var page=operations.rewards(readScope(),"campaign",null,2);assertEquals(2,page.items().size());assertNotNull(page.nextCursor());
        var next=operations.rewards(readScope(),"campaign",page.nextCursor(),2);assertEquals(1,next.items().size());assertNull(next.nextCursor());
        assertEquals(3,java.util.stream.Stream.concat(page.items().stream(),next.items().stream()).map(com.acme.marketing.referral.application.query.ReferralOperationsService.Reward::rewardId).distinct().count());
        assertTrue(page.items().stream().allMatch(r->r.deliveryState().equals("NOT_SUBMITTED") && r.quotaState().equals("WAIT_QUOTA")));
        String payload=facts.json.writeValueAsString(page);assertFalse(payload.contains("beneficiaryKey"));assertFalse(payload.contains("cipher"));assertFalse(payload.contains("subject-B"));
        assertEquals(1L,operations.participants(readScope(),"campaign",null,20).items().getFirst().validCount());assertTrue(operations.relations(readScope(),"campaign",null,20).items().getFirst().counted());
    }
    @Test void operationsEnforceTenantOrganizationShopPermissionAndPageBounds(){
        bilateralRules();run();
        var org=new TenantScope(new TenantId(binding.tenant),Set.of("other"),Set.of("shop"),"operator",Set.of("referral:read"));assertTrue(operations.rewards(org,"campaign",null,20).items().isEmpty());
        var shop=new TenantScope(new TenantId(binding.tenant),Set.of("org"),Set.of("other"),"operator",Set.of("referral:read"));assertTrue(operations.relations(shop,"campaign",null,20).items().isEmpty());
        var tenant=new TenantScope(new TenantId("other"),Set.of("org"),Set.of("shop"),"operator",Set.of("referral:read"));assertTrue(operations.participants(tenant,"campaign",null,20).items().isEmpty());
        assertThrows(RuntimeException.class,()->operations.rewards(scope(),"campaign",null,20));assertThrows(IllegalArgumentException.class,()->operations.rewards(readScope(),"campaign",null,101));
        assertThrows(IllegalArgumentException.class,()->operations.rewards(readScope(),"campaign","' OR 1=1",20));
    }
    @Test void publicOperationsHttpRejectsAnonymousAccess() throws Exception {
        try(var client=java.net.http.HttpClient.newHttpClient()){
            var response=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+port+"/api/v1/referral-campaigns/campaign/rewards")).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());
            assertTrue(response.statusCode()==401 || response.statusCode()==403,"unauthenticated request must not read data");assertFalse(response.body().contains(binding.tenant));
        }
    }
    TenantScope fulfillmentScope(){return new TenantScope(new TenantId(binding.tenant),Set.of("org"),Set.of("shop"),"provider-machine",Set.of("referral:fulfillment"));}
    String confirmedReward(){String reward=reservedReward();authorizer(()->{}).confirm(authorizationScope(),authorizationRequest(reward));return reward;}
    ReferralFulfillmentService fulfiller(long revision,String delivery,String compensation,String business,Runnable hook){
        ReferralFulfillmentProofPort proof=(scope,envelope,reward)->{
            Instant issued=binding.clock.instant();hook.run();
            var snapshot=new Snapshot(binding.tenant,reward.rewardId(),reward.sourceRequestId(),"fixture-provider",revision,delivery,compensation,
                "sha256:"+com.acme.marketing.platform.crypto.Digests.sha256Hex(business),delivery.equals("SUCCEEDED")?"sha256:"+"c".repeat(64):null,
                compensation.equals("REVERSED")?"sha256:"+"d".repeat(64):delivery.equals("FAILED_FINAL")?"sha256:"+"e".repeat(64):null);
            return new Proof(envelope,reward,snapshot,issued,issued.plusSeconds(10));};
        return new ReferralFulfillmentService(fulfillmentMapper,authorizationMapper,rewardMapper,quotas,proof,binding.clock,facts.json,manager,20);
    }
    Envelope fulfillment(String reward,String event){return new Envelope(reward,event,"fixture-assertion","fixture-payload");}
    String rewardState(String column){return jdbc.queryForObject("SELECT "+column+" FROM mk_referral_reward WHERE tenant_id=?",String.class,binding.tenant);}
    long bucketCount(String column){return jdbc.queryForObject("SELECT "+column+" FROM mk_referral_quota_bucket WHERE tenant_id=?",Long.class,binding.tenant);}
    @Test void providerSuccessConsumesExactlyOnceAndUnknownDoesNotRelease(){
        String reward=confirmedReward();fulfiller(1,"UNKNOWN","NONE","unknown",()->{}).accept(fulfillmentScope(),fulfillment(reward,"unknown"));
        assertEquals("RESERVED",rewardState("quota_state"));assertEquals(1,bucketCount("reserved"));
        var envelope=fulfillment(reward,"success");var provider=fulfiller(2,"SUCCEEDED","NONE","success",()->{});var first=provider.accept(fulfillmentScope(),envelope);
        assertEquals(first,provider.accept(fulfillmentScope(),envelope));assertEquals("CONSUMED",rewardState("quota_state"));assertEquals(1,bucketCount("consumed"));assertEquals(0,bucketCount("reserved"));assertEquals(2,count("mk_referral_fulfillment_inbox"));
        assertEquals(1L,jdbc.queryForObject("SELECT consumed_count FROM mk_referral_subject_quota WHERE tenant_id=?",Long.class,binding.tenant));
    }
    @Test void reversalBeforeSuccessMessageRetainsSuccessAndConsumedQuota(){
        String reward=confirmedReward();fulfiller(3,"SUCCEEDED","REVERSED","reversed",()->{}).accept(fulfillmentScope(),fulfillment(reward,"reverse"));
        assertEquals("REPLAYED_OLD_REVISION",fulfiller(2,"SUCCEEDED","NONE","success",()->{}).accept(fulfillmentScope(),fulfillment(reward,"late-success")).outcome());
        fulfiller(4,"SUCCEEDED","REVERSED","reverse-new-revision",()->{}).accept(fulfillmentScope(),fulfillment(reward,"reverse-replay"));
        assertEquals("SUCCEEDED",rewardState("delivery_state"));assertEquals("REVERSED",rewardState("compensation_state"));assertEquals("CONSUMED",rewardState("quota_state"));assertEquals(1,bucketCount("consumed"));assertEquals(99,bucketCount("available"));
        assertEquals("sha256:"+"c".repeat(64),jdbc.queryForObject("SELECT success_digest FROM mk_referral_quota_reservation WHERE tenant_id=?",String.class,binding.tenant));
    }
    @Test void provenNonIssuanceReleasesAndNeverAcceptsLaterSuccess(){
        String reward=confirmedReward();fulfiller(1,"FAILED_FINAL","CANCELLED","failed",()->{}).accept(fulfillmentScope(),fulfillment(reward,"failed"));
        assertEquals("RELEASED",rewardState("quota_state"));assertEquals(100,bucketCount("available"));
        assertThrows(ConflictException.class,()->fulfiller(2,"SUCCEEDED","NONE","impossible",()->{}).accept(fulfillmentScope(),fulfillment(reward,"bad")));
        assertEquals(1,count("mk_referral_fulfillment_history"));assertEquals(1,count("mk_referral_fulfillment_inbox"));
    }
    @Test void refundDuringProviderVerificationPreservesSuccessAndRequestsCompensation(){
        String reward=confirmedReward();fulfiller(1,"SUCCEEDED","NONE","success",()->{risk=Risk.REJECT;signal();run();}).accept(fulfillmentScope(),fulfillment(reward,"success"));
        assertEquals("INVALIDATED",rewardState("entitlement_state"));assertEquals("SUCCEEDED",rewardState("delivery_state"));assertEquals("PENDING",rewardState("compensation_state"));assertEquals("CONSUMED",rewardState("quota_state"));
    }
    @Test void laterQualificationInvalidationCannotEraseConfirmedReversal(){
        String reward=confirmedReward();fulfiller(1,"SUCCEEDED","REVERSED","reverse",()->{}).accept(fulfillmentScope(),fulfillment(reward,"reverse"));
        risk=Risk.REJECT;signal();run();assertEquals("REVERSED",rewardState("compensation_state"));assertEquals("SUCCEEDED",rewardState("delivery_state"));assertEquals(1,bucketCount("consumed"));
    }
    @Test void sameProviderRevisionOrEventWithDifferentFactsIsRejected(){
        String reward=confirmedReward();fulfiller(1,"ACCEPTED","NONE","accepted",()->{}).accept(fulfillmentScope(),fulfillment(reward,"event"));
        assertThrows(ConflictException.class,()->fulfiller(1,"SUCCEEDED","NONE","different",()->{}).accept(fulfillmentScope(),fulfillment(reward,"new-event")));
        assertThrows(ConflictException.class,()->fulfiller(2,"SUCCEEDED","NONE","different",()->{}).accept(fulfillmentScope(),fulfillment(reward,"event")));
        assertEquals(1,count("mk_referral_fulfillment_history"));assertEquals("RESERVED",rewardState("quota_state"));
    }
    @Test void providerCannotDowngradeSuccessOrClearManualReview(){
        String reward=confirmedReward();fulfiller(1,"SUCCEEDED","MANUAL_REVIEW","manual",()->{}).accept(fulfillmentScope(),fulfillment(reward,"manual"));
        fulfiller(2,"SUCCEEDED","NONE","success-again",()->{}).accept(fulfillmentScope(),fulfillment(reward,"success"));assertEquals("MANUAL_REVIEW",rewardState("compensation_state"));
        assertThrows(ConflictException.class,()->fulfiller(3,"UNKNOWN","NONE","unknown",()->{}).accept(fulfillmentScope(),fulfillment(reward,"unknown")));assertEquals(1,bucketCount("consumed"));
    }
    @Test void defaultFulfillmentProofAndMissingAuthorizationCannotCreateProviderFacts(){
        String reward=reservedReward();assertThrows(ConflictException.class,()->fulfiller(1,"SUCCEEDED","NONE","success",()->{}).accept(fulfillmentScope(),fulfillment(reward,"event")));
        var unavailable=new ReferralFulfillmentService(fulfillmentMapper,authorizationMapper,rewardMapper,quotas,new com.acme.marketing.referral.infrastructure.ReferralFulfillmentConfiguration().referralFulfillmentProofs(),binding.clock,facts.json,manager,20);
        assertThrows(ConflictException.class,()->unavailable.accept(fulfillmentScope(),fulfillment(reward,"event")));assertEquals(0,count("mk_referral_fulfillment_inbox"));assertEquals("RESERVED",rewardState("quota_state"));
    }
    @Test void expirationAfterFulfillmentSqlRollsBackInboxSuccessAndAllQuotaCounts(){
        String reward=confirmedReward();int previous=count("mk_referral_outbox");
        var delayed=(com.acme.marketing.referral.infrastructure.persistence.mapper.ReferralFulfillmentMapper)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{com.acme.marketing.referral.infrastructure.persistence.mapper.ReferralFulfillmentMapper.class},(p,m,a)->{
            try{Object result=m.invoke(fulfillmentMapper,a);if(m.getName().equals("insertInbox"))binding.clock.now.set(NOW.plusSeconds(10));return result;}catch(InvocationTargetException failed){throw failed.getCause();}});
        ReferralFulfillmentProofPort proof=(scope,envelope,row)->new Proof(envelope,row,new Snapshot(binding.tenant,row.rewardId(),row.sourceRequestId(),"fixture-provider",1,"SUCCEEDED","NONE","sha256:"+"b".repeat(64),"sha256:"+"c".repeat(64),null),NOW,NOW.plusSeconds(10));
        var expired=new ReferralFulfillmentService(delayed,authorizationMapper,rewardMapper,quotas,proof,binding.clock,facts.json,manager,20);
        assertThrows(ConflictException.class,()->expired.accept(fulfillmentScope(),fulfillment(reward,"event")));assertEquals(0,count("mk_referral_fulfillment_inbox"));assertEquals(0,count("mk_referral_fulfillment_history"));assertEquals("NOT_SUBMITTED",rewardState("delivery_state"));assertEquals("RESERVED",rewardState("quota_state"));assertEquals(1,bucketCount("reserved"));assertEquals(previous,count("mk_referral_outbox"));
    }
    @Test void summaryCountsDatabaseProjectionsWithoutJoinMultiplicationOrFalseWatermark(){
        var before=operations.summary(readScope(),"campaign");assertEquals(1,before.counts().participants());assertEquals(1,before.counts().relations());assertEquals(1,before.counts().awaitingEvaluation());assertEquals(0,before.counts().rewards());assertNull(before.eventWatermark());
        var admin=new TenantScope(new TenantId(binding.tenant),Set.of("*"),Set.of("*"),"admin",Set.of("referral:read"));assertEquals(before.counts(),operations.summary(admin,"campaign").counts());assertEquals(1,operations.participants(admin,"campaign",null,20).items().size());
        registered=true;bilateralRules();run();var second=bind("subject-C");service.process(scope(),second.relationId(),"worker","trace");
        var summary=operations.summary(readScope(),"campaign");assertEquals(2,summary.counts().relations());assertEquals(2,summary.counts().projectedValidRelations());assertEquals(2,summary.counts().everQualifiedRelations());assertEquals(0,summary.counts().awaitingEvaluation());assertEquals(count("mk_referral_reward"),summary.counts().rewards());assertEquals(0,summary.counts().succeededRewards());
        assertEquals(0,operations.summary(readScope(),"other-campaign").counts().participants());assertThrows(RuntimeException.class,()->operations.summary(scope(),"campaign"));
    }
    @Test void summaryKeepsSuccessAndReversalAsIndependentCounters(){
        String reward=confirmedReward();fulfiller(1,"SUCCEEDED","REVERSED","reverse",()->{}).accept(fulfillmentScope(),fulfillment(reward,"reverse"));risk=Risk.REJECT;signal();run();
        var counts=operations.summary(readScope(),"campaign").counts();assertEquals(1,counts.succeededRewards());assertEquals(1,counts.reversedRewards());assertEquals(1,counts.invalidatedRewards());assertEquals(0,counts.pendingCompensation());assertEquals(0,counts.projectedValidRelations());assertEquals(1,counts.everQualifiedRelations());
    }
    @Test void oldTerminalContradictionCannotHideBehindSmallerRevision(){
        String reward=confirmedReward();fulfiller(3,"FAILED_FINAL","CANCELLED","failed",()->{}).accept(fulfillmentScope(),fulfillment(reward,"failed"));
        assertThrows(ConflictException.class,()->fulfiller(1,"SUCCEEDED","NONE","contradiction",()->{}).accept(fulfillmentScope(),fulfillment(reward,"old-success")));
        assertEquals(1,count("mk_referral_fulfillment_history"));assertEquals(1,count("mk_referral_fulfillment_inbox"));assertEquals(100,bucketCount("available"));
    }
    @Test void oldReversalCannotBeAcknowledgedAsNormalCompletedSuccess(){
        String reward=confirmedReward();fulfiller(3,"SUCCEEDED","NONE","success",()->{}).accept(fulfillmentScope(),fulfillment(reward,"success"));
        assertThrows(ConflictException.class,()->fulfiller(1,"SUCCEEDED","REVERSED","reversed",()->{}).accept(fulfillmentScope(),fulfillment(reward,"old-reverse")));
        assertEquals(1,count("mk_referral_fulfillment_history"));assertEquals("NONE",rewardState("compensation_state"));
    }
    TenantScope reevaluationScope(){return new TenantScope(new TenantId(binding.tenant),Set.of("org"),Set.of("shop"),"support-agent",Set.of("referral:reevaluate"));}
    @Test void reevaluationPermanentlyReplaysOneQueueRequestAndDoesNotOverrideReward(){
        String reward=reservedReward();long revision=task().requestedRevision();var first=reevaluations.submit(reevaluationScope(),reward,"reevaluate-key-one","verify evidence");
        assertEquals("QUEUED",first.state());assertEquals(1,first.relationCount());assertEquals(revision+1,task().requestedRevision());
        assertEquals(first,reevaluations.submit(reevaluationScope(),reward,"reevaluate-key-one","verify evidence"));assertEquals(revision+1,task().requestedRevision());
        assertEquals("RESERVED",rewardState("quota_state"));assertEquals("NONE",rewardState("authorization_state"));assertEquals("NOT_SUBMITTED",rewardState("delivery_state"));
        risk=Risk.REJECT;assertFalse(run().counted());assertEquals("INVALIDATED",rewardState("entitlement_state"));assertEquals("RELEASED",rewardState("quota_state"));
        risk=Risk.ALLOW;reevaluations.submit(reevaluationScope(),reward,"reevaluate-key-two","source recovered");assertTrue(run().counted());assertEquals("INVALIDATED",rewardState("entitlement_state"));assertEquals(1,count("mk_referral_reward"));
    }
    @Test void reevaluationRejectsChangedReasonMissingPermissionAndDifferentTenant(){
        String reward=reservedReward();reevaluations.submit(reevaluationScope(),reward,"reevaluate-key-one","verify evidence");
        assertThrows(ConflictException.class,()->reevaluations.submit(reevaluationScope(),reward,"reevaluate-key-one","different reason"));
        assertThrows(RuntimeException.class,()->reevaluations.submit(readScope(),reward,"reevaluate-key-two","verify evidence"));
        var foreign=new TenantScope(new TenantId("other-tenant"),Set.of("*"),Set.of("*"),"support",Set.of("referral:reevaluate"));assertThrows(ConflictException.class,()->reevaluations.submit(foreign,reward,"reevaluate-key-two","verify evidence"));
        assertThrows(IllegalArgumentException.class,()->reevaluations.submit(reevaluationScope(),reward,"reevaluate-key-two"," "));assertEquals(1,count("mk_referral_reevaluation_command"));
    }
    @Test void milestoneReevaluationQueuesAllRelationsAndRejectsOverBudgetAtomically(){
        bilateralRules();run();var second=bind("subject-C");service.process(scope(),second.relationId(),"worker","trace");String reward=jdbc.queryForObject("SELECT reward_id FROM mk_referral_reward WHERE tenant_id=? AND rule_id='tier-two'",String.class,binding.tenant);
        var limited=new com.acme.marketing.referral.application.reevaluation.ReferralReevaluationService(authorizationMapper,reevaluationMapper,repository,rewardMapper,facts.json,binding.clock,manager,1);
        long revision=task().requestedRevision();assertThrows(ConflictException.class,()->limited.submit(reevaluationScope(),reward,"reevaluate-tier-one","verify all"));assertEquals(0,count("mk_referral_reevaluation_command"));assertEquals(revision,task().requestedRevision());
        assertEquals(2,reevaluations.submit(reevaluationScope(),reward,"reevaluate-tier-one","verify all").relationCount());assertEquals(revision+1,task().requestedRevision());assertEquals("PENDING",new TransactionTemplate(manager).execute(tx->repository.lockTask(binding.tenant,second.relationId())).status());
    }
    @Test void reevaluationOutboxFailureRollsBackReceiptAndEveryQueueSignal(){
        String reward=reservedReward();long revision=task().requestedRevision();int events=count("mk_referral_outbox");
        var failing=org.mockito.Mockito.mock(com.acme.marketing.referral.infrastructure.persistence.mapper.ReferralRewardMapper.class,org.mockito.AdditionalAnswers.delegatesTo(rewardMapper));
        org.mockito.Mockito.doAnswer(call->{rewardMapper.outbox(call.getArgument(0));throw new IllegalStateException("after queued outbox");}).when(failing).outbox(org.mockito.ArgumentMatchers.any());
        var service=new com.acme.marketing.referral.application.reevaluation.ReferralReevaluationService(authorizationMapper,reevaluationMapper,repository,failing,facts.json,binding.clock,manager,100);
        assertThrows(IllegalStateException.class,()->service.submit(reevaluationScope(),reward,"reevaluate-key-one","verify evidence"));assertEquals(0,count("mk_referral_reevaluation_command"));assertEquals(revision,task().requestedRevision());assertEquals(events,count("mk_referral_outbox"));
    }
    @Test void concurrentReevaluationRetriesOnlySignalOnce() throws Exception {
        String reward=reservedReward();long revision=task().requestedRevision();
        try(var pool=Executors.newFixedThreadPool(2)){var start=new CountDownLatch(1);var a=pool.submit(()->{start.await();return reevaluations.submit(reevaluationScope(),reward,"reevaluate-key-one","verify evidence");});var b=pool.submit(()->{start.await();return reevaluations.submit(reevaluationScope(),reward,"reevaluate-key-one","verify evidence");});start.countDown();assertEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));}
        assertEquals(revision+1,task().requestedRevision());assertEquals(1,count("mk_referral_reevaluation_command"));
    }
    @Test void allV5CommentsPresentAndTasksHaveNoImplicitParentLocks(){
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('mk_referral_qualification','mk_referral_progress','mk_referral_evaluation_task')",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name LIKE 'mk_referral_%' AND column_comment=''",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.key_column_usage WHERE table_schema=DATABASE() AND table_name='mk_referral_evaluation_task' AND referenced_table_name IS NOT NULL",Integer.class));
    }
    interface Call{Object run(Method method,Object[] args)throws Throwable;}
    ReferralQualificationRepository proxy(Call call){return (ReferralQualificationRepository)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{ReferralQualificationRepository.class},(p,m,a)->call.run(m,a));}
    Object invoke(Method method,Object[] args)throws Throwable{try{return method.invoke(repository,args);}catch(InvocationTargetException failed){throw failed.getCause();}}
}
