package com.acme.marketing.benefit;

import com.acme.marketing.benefit.application.*;
import com.acme.marketing.benefit.application.ReferralIntakeConfirmationPort.*;
import com.acme.marketing.benefit.application.ReferralIntakeIdentityPort.Binding;
import com.acme.marketing.benefit.domain.ReferralAwardPreparation.*;
import com.acme.marketing.benefit.infrastructure.persistence.*;
import com.acme.marketing.benefit.infrastructure.persistence.mapper.*;
import com.acme.marketing.contracts.referral.*;
import com.acme.marketing.platform.error.ConflictException;
import java.sql.Connection;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/** 同一临时MySQL验证新HELD原子编排；仅显式复用旧测试启动器，不运行旧类测试或任何应用worker。 */
class ReferralAwardIntakeMySqlTest {
    static final Instant NOW=ReferralAwardIntentAssemblerTest.NOW;
    static ReferralPreparationMapper prepareMapper;static ReferralIntakeMapper intakeMapper;static AwardIntentRelayMapper oldRelay;
    Fixture f;
    @BeforeAll static void database() throws Exception {
        ReferralPreparationMySqlTest.database();
        var factory=new SqlSessionFactoryBean();factory.setDataSource(ReferralPreparationMySqlTest.datasource);
        var configuration=new org.apache.ibatis.session.Configuration();configuration.setArgNameBasedConstructorAutoMapping(true);configuration.setLocalCacheScope(org.apache.ibatis.session.LocalCacheScope.STATEMENT);factory.setConfiguration(configuration);
        factory.setMapperLocations(new ClassPathResource("mapper/ReferralPreparationMapper.xml"),new ClassPathResource("mapper/ReferralIntakeMapper.xml"),new ClassPathResource("mapper/AwardIntentRelayMapper.xml"));
        var session=new SqlSessionTemplate(factory.getObject());prepareMapper=session.getMapper(ReferralPreparationMapper.class);intakeMapper=session.getMapper(ReferralIntakeMapper.class);oldRelay=session.getMapper(AwardIntentRelayMapper.class);
    }
    @AfterAll static void close(){ReferralPreparationMySqlTest.close();}
    @BeforeEach void fixture() throws Exception{f=new Fixture();}

    @Test void v11AtomicHeldAcceptanceHasEncryptedReferenceAndIsInvisibleToOldRelay() {
        var accepted=f.call(f.token());assertEquals(ReferralAwardIntakeService.Status.HELD_ACCEPTED,accepted.status());assertEquals(1,f.count("mk_referral_award_held_outbox"));assertEquals(1,f.count("mk_referral_award_expected_fact"));
        assertEquals(Phase.ACCEPTED,f.preparations.find("tenant",f.request()).orElseThrow().state().phase());assertEquals(accepted.intentId(),f.contexts.find("tenant",f.request()).acceptedIntentId());
        assertTrue(oldRelay.selectCandidates("9999-01-01T00:00:00Z",10,100).isEmpty());
        assertEquals(0,ReferralPreparationMySqlTest.jdbc.queryForObject("SELECT COUNT(*) FROM mk_award_intent_outbox",Integer.class));
        assertEquals(0,ReferralPreparationMySqlTest.jdbc.queryForObject("SELECT COUNT(*) FROM mk_benefit_outbox",Integer.class));
        // 验证本切片 V11 已应用，不把后续合法迁移视为失败。
        assertEquals(1,ReferralPreparationMySqlTest.jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1 AND type='SQL' AND version='11'",Integer.class));
        assertEquals(0,ReferralPreparationMySqlTest.jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name IN ('mk_referral_award_intake','mk_referral_award_held_outbox','mk_referral_award_expected_fact') AND column_comment=''",Integer.class));
    }
    @Test void expiredTokenOriginalReplayKeepsOneCipherReceiptIntentAndExpectedFact() {
        var original=f.call(f.token());byte[] cipher=f.preparations.find("tenant",f.request()).orElseThrow().snapshot().encrypted().ciphertext();
        f.inputs.clock.now=NOW.plusSeconds(1000);var replay=f.call(null);
        assertTrue(replay.replay());assertEquals(original.intentId(),replay.intentId());assertEquals(1,f.confirms);
        assertArrayEquals(cipher,f.preparations.find("tenant",f.request()).orElseThrow().snapshot().encrypted().ciphertext());assertEquals(1,f.count("mk_referral_award_expected_fact"));
    }
    @Test void remoteConfirmedThenLocalFailureRollsBackAllLocalAcceptanceAndRecoversSameIdentity() {
        f.failAfterHold=true;assertThrows(RuntimeException.class,()->f.call(f.token()));
        assertEquals(Phase.CONFIRMING,f.preparations.find("tenant",f.request()).orElseThrow().state().phase());assertNull(f.contexts.find("tenant",f.request()).confirmation());assertEquals(0,f.count("mk_referral_award_held_outbox"));assertEquals(0,f.count("mk_referral_award_expected_fact"));
        f.failAfterHold=false;f.inputs.clock.now=NOW.plusSeconds(11);
        assertEquals(ReferralAwardIntakeService.Status.HELD_ACCEPTED,f.call(null).status());assertEquals(1,f.recovers);assertEquals(1,f.confirms);assertEquals(1,f.count("mk_referral_award_expected_fact"));
    }
    @Test void cancellationReceiptCommitsMonotonicBarrierAndCannotProduceHeldRows() {
        f.remoteState=State.CANCEL_REQUESTED;f.revision=3;f.cancelRevision=3;
        assertEquals(ReferralAwardIntakeService.Status.CANCEL_PENDING,f.call(f.token()).status());
        var cancelled=f.contexts.find("tenant",f.request());assertTrue(cancelled.cancelled());assertNotNull(cancelled.confirmation().confirmationId());assertEquals(0,f.count("mk_referral_award_held_outbox"));
        ReferralPreparationMySqlTest.tx.executeWithoutResult(s->f.contexts.observe(f.binding,f.response(State.CONFIRMED,2,0,"confirmation")));
        assertTrue(f.contexts.find("tenant",f.request()).cancelled());f.inputs.clock.now=NOW.plusSeconds(11);
        assertEquals(ReferralAwardIntakeService.Status.CANCEL_PENDING,f.call(null).status());assertEquals(1,f.confirms);
    }
    @Test void sameWatermarkRefreshIsReplayButDifferentPermanentReceiptQuarantinesHeldRows() {
        f.call(f.token());f.inputs.clock.now=NOW.plusSeconds(1);
        ReferralPreparationMySqlTest.tx.executeWithoutResult(s->f.contexts.observe(f.binding,f.response(State.CONFIRMED,2,0,"confirmation")));
        assertFalse(f.contexts.find("tenant",f.request()).quarantined());
        ReferralPreparationMySqlTest.tx.executeWithoutResult(s->f.contexts.observe(f.binding,f.response(State.CONFIRMED,2,0,"different")));
        assertTrue(f.contexts.find("tenant",f.request()).quarantined());
        assertEquals("CANCEL_HELD",ReferralPreparationMySqlTest.jdbc.queryForObject("SELECT state_name FROM mk_referral_award_held_outbox WHERE tenant_id='tenant' AND source_request_id=?",String.class,f.request()));
        assertEquals(ReferralAwardIntakeService.Status.QUARANTINED,f.call(null).status());assertEquals(1,f.confirms);
    }
    @Test void riskUnavailableSurvivesFreshResigningWithoutChangingOriginalContextOrCipher() {
        f.action=ReferralIntakeRiskPort.Action.UNAVAILABLE;assertEquals(ReferralAwardIntakeService.Status.PENDING_RISK,f.call(f.token()).status());
        var original=f.contexts.find("tenant",f.request());byte[] cipher=f.preparations.find("tenant",f.request()).orElseThrow().snapshot().encrypted().ciphertext();
        f.inputs.clock.now=NOW.plusSeconds(11);f.refresh();f.action=ReferralIntakeRiskPort.Action.ALLOW;
        assertEquals(ReferralAwardIntakeService.Status.HELD_ACCEPTED,f.call(f.token()).status());
        assertEquals(original.authorizationIssuedAt(),f.contexts.find("tenant",f.request()).authorizationIssuedAt());assertArrayEquals(cipher,f.preparations.find("tenant",f.request()).orElseThrow().snapshot().encrypted().ciphertext());assertEquals(1,f.confirms);
    }
    @Test void finalWriteDelayPastCurrentProofDeadlineRollsBackReceiptAndAllHeldRows() {
        f.expireAfterHold=true;assertThrows(ConflictException.class,()->f.call(f.token()));
        assertEquals(Phase.CONFIRMING,f.preparations.find("tenant",f.request()).orElseThrow().state().phase());assertNull(f.contexts.find("tenant",f.request()).confirmation());assertEquals(0,f.count("mk_referral_award_held_outbox"));assertEquals(0,f.count("mk_referral_award_expected_fact"));
    }
    @Test void actualPreparationRowLockWaitRechecksNanosecondConfirmationExpiry() throws Exception {
        CountDownLatch blocked=new CountDownLatch(1);
        try(Connection blocker=ReferralPreparationMySqlTest.datasource.getConnection();var executor=Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);f.narrowProof=true;
            f.confirmHook=()->{try(var lock=blocker.prepareStatement("SELECT state_version FROM mk_referral_award_preparation WHERE tenant_id='tenant' AND source_request_id=? FOR UPDATE")){lock.setString(1,f.request());lock.executeQuery().close();blocked.countDown();}catch(Exception e){throw new IllegalStateException(e);}};
            var waiting=executor.submit(()->f.call(f.token()));
            try {
                assertTrue(blocked.await(30,TimeUnit.SECONDS));ReferralPreparationMySqlTest.awaitLockWait();assertFalse(waiting.isDone());f.inputs.clock.now=NOW.plusNanos(600);blocker.commit();
                var error=assertThrows(ExecutionException.class,()->waiting.get(15,TimeUnit.SECONDS));assertInstanceOf(ConflictException.class,error.getCause());assertEquals("REFERRAL_INTAKE_UNAVAILABLE",((ConflictException)error.getCause()).code());
            } finally {blocker.rollback();}
        }
        assertEquals(Phase.CONFIRMING,f.preparations.find("tenant",f.request()).orElseThrow().state().phase());assertEquals(0,f.count("mk_referral_award_expected_fact"));
    }

    static class Fixture {
        final ReferralAwardIntentAssemblerTest.Fixture inputs=new ReferralAwardIntentAssemblerTest.Fixture();final Binding binding;
        final ReferralPreparationRepository preparations;final MybatisReferralIntakeRepository contexts;final ReferralCandidateSnapshotService protection;
        int confirms,recovers;boolean unknown,failAfterHold,expireAfterHold,narrowProof;State remoteState=State.CONFIRMED;long revision=2,cancelRevision;
        ReferralIntakeRiskPort.Action action=ReferralIntakeRiskPort.Action.ALLOW;Runnable confirmHook=()->{};
        Fixture() throws Exception {
            var c=inputs.claims;String reward="intake-"+UUID.randomUUID();
            inputs.claims=new ReferralAwardAuthorizationClaims(c.issuer(),c.audience(),c.tenantId(),c.organizationId(),c.shopId(),reward,ReferralAwardIdentity.sourceRequestId("tenant",reward),c.campaignId(),c.definitionId(),c.definitionVersion(),c.generation(),c.artifactId(),c.artifactHash(),c.participantId(),c.relationId(),c.milestone(),c.beneficiarySubject(),c.role(),c.ruleId(),c.quantity(),c.qualificationRevision(),c.issuedAt(),c.expiresAt());c=inputs.claims;
            var candidate=inputs.assemble();var id=ReferralCandidateSnapshotTest.identity(candidate);
            binding=new Binding(id,c.organizationId(),c.shopId(),c.campaignId(),c.definitionId(),c.definitionVersion(),c.generation(),c.artifactId(),c.artifactHash(),c.participantId(),c.relationId(),c.milestone(),c.role().name(),c.ruleId(),c.quantity());
            preparations=new MybatisReferralPreparationRepository(prepareMapper,inputs.clock);contexts=new MybatisReferralIntakeRepository(intakeMapper,new ObjectMapper());protection=new ReferralCandidateSnapshotService(new ReferralCandidateSnapshotTest.ProtectionFixture(),new ObjectMapper());
        }
        ReferralAwardIntakeService service(){
            ReferralIntakeRepository controlled=new ReferralIntakeRepository(){
                public Context reserve(Binding b,Instant i,Instant e){return contexts.reserve(b,i,e);}public Context lock(Binding b){return contexts.lock(b);}public Context find(String t,String r){return contexts.find(t,r);}public Context observe(Binding b,Result r){return contexts.observe(b,r);}public void recordRisk(Binding b,ReferralIntakeRiskPort.Decision d){contexts.recordRisk(b,d);}
                public void hold(Binding b,String id,Instant now){contexts.hold(b,id,now);if(failAfterHold)throw new IllegalStateException("injected local failure after all SQL writes");if(expireAfterHold)inputs.clock.now=NOW.plusSeconds(5);}
            };
            return new ReferralAwardIntakeService(inputs.service(),preparations,controlled,protection,(scope,request)->{outside();return binding;},(b,p)->{outside();return new ReferralIntakeRiskPort.Decision(b.identity(),action,"risk-proof",inputs.clock.instant(),inputs.clock.instant().plusSeconds(5));},new ReferralIntakeConfirmationPort(){
                public Result confirm(Identity i){outside();confirms++;assertEquals("CONFIRMING",ReferralPreparationMySqlTest.jdbc.queryForObject("SELECT phase FROM mk_referral_award_preparation WHERE tenant_id='tenant' AND source_request_id=?",String.class,request()));confirmHook.run();return unknown?null:response(remoteState,revision,cancelRevision,"confirmation");}
                public Result recover(Identity i){outside();recovers++;return unknown?null:response(remoteState,revision,cancelRevision,"confirmation");}
            },inputs.modes,inputs.clock,new DataSourceTransactionManager(ReferralPreparationMySqlTest.datasource),true,Duration.ofSeconds(10),Duration.ofSeconds(10));
        }
        Result response(State state,long revision,long cancelled,String id){return new Result(binding.identity(),state,revision,cancelled,id,1,NOW,null,inputs.clock.instant(),inputs.clock.instant().plus(narrowProof?Duration.ofNanos(500):Duration.ofSeconds(5)));}
        ReferralAwardIntakeService.View call(String token){return service().intake(inputs.scope,request(),token);}
        String token(){return inputs.token();}String request(){return binding.identity().sourceRequestId();}
        void refresh(){var now=inputs.clock.instant();inputs.claims=inputs.claims(inputs.claims.role(),inputs.claims.relationId(),inputs.claims.milestone(),now,now.plusSeconds(5));
            // 保留本fixture随机永久reward/source，只有签发时间可刷新。
            var c=inputs.claims;inputs.claims=new ReferralAwardAuthorizationClaims(c.issuer(),c.audience(),c.tenantId(),c.organizationId(),c.shopId(),binding.identity().rewardId(),request(),c.campaignId(),c.definitionId(),c.definitionVersion(),c.generation(),c.artifactId(),c.artifactHash(),c.participantId(),c.relationId(),c.milestone(),c.beneficiarySubject(),c.role(),c.ruleId(),c.quantity(),c.qualificationRevision(),c.issuedAt(),c.expiresAt());inputs.afterProof=now;inputs.proofIssued=now;inputs.proofExpiry=now.plusSeconds(5);}
        int count(String table){return ReferralPreparationMySqlTest.jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE tenant_id='tenant' AND source_request_id=?",Integer.class,request());}
        void outside(){assertFalse(TransactionSynchronizationManager.isActualTransactionActive());}
    }
}
