package com.acme.marketing.benefit;
import com.acme.marketing.benefit.application.*;
import com.acme.marketing.benefit.application.ReferralIntakeConfirmationPort.*;
import com.acme.marketing.benefit.application.ReferralHeldReviewRepository.*;
import com.acme.marketing.benefit.infrastructure.persistence.*;
import com.acme.marketing.benefit.infrastructure.persistence.mapper.ReferralHeldReviewMapper;
import java.sql.Connection;
import java.time.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.mybatis.spring.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/** V12唯一临时数据库专项；不运行旧V11方法、不启动应用worker、不接受外部URL。 */
class ReferralHeldReviewMySqlTest {
    static ReferralHeldReviewMapper mapper;Fixture f;
    @BeforeAll static void database() throws Exception {
        ReferralAwardIntakeMySqlTest.database();var factory=new SqlSessionFactoryBean();factory.setDataSource(ReferralPreparationMySqlTest.datasource);
        var c=new org.apache.ibatis.session.Configuration();c.setLocalCacheScope(org.apache.ibatis.session.LocalCacheScope.STATEMENT);factory.setConfiguration(c);
        factory.setMapperLocations(new ClassPathResource("mapper/ReferralHeldReviewMapper.xml"));mapper=new SqlSessionTemplate(factory.getObject()).getMapper(ReferralHeldReviewMapper.class);
    }
    @AfterAll static void close(){ReferralAwardIntakeMySqlTest.close();}
    @BeforeEach void fixture() throws Exception {f=new Fixture();}
    @Test void v12CheckedIsOnlyObservationWithExactNanosAndNoDelivery() {
        f.riskLifetime=Duration.ofNanos(500);var checked=f.review().observation();assertEquals(Status.CHECKED,checked.status());assertEquals(f.now.plusNanos(500),checked.validUntil());
        assertEquals(checked,f.repo.find("tenant",f.base.request()));assertEquals("HELD",f.held());assertEquals(1,f.base.count("mk_referral_award_expected_fact"));assertEquals(1,f.base.confirms);
        assertEquals(12,ReferralPreparationMySqlTest.jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE type='SQL' AND success=1",Integer.class));
        assertEquals(0,ReferralPreparationMySqlTest.jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='mk_referral_award_held_review' AND column_comment=''",Integer.class));
    }
    @Test void acceptedCancellationSyncWorksAfterModeRollbackAndOriginalSuccessKeepsBarrier() {
        var receipt=f.base.preparations.find("tenant",f.base.request()).orElseThrow().state().receipt();f.state=State.CANCEL_REQUESTED;f.revision=3;f.cancel=3;
        f.base.inputs.modes=new AwardDispatchModeRouter("LEGACY","");assertEquals(Status.CANCELLED,f.sync().observation().status());assertEquals(0,f.risks);assertEquals("CANCEL_HELD",f.held());
        assertEquals(receipt,f.base.preparations.find("tenant",f.base.request()).orElseThrow().state().receipt());f.base.inputs.modes=new AwardDispatchModeRouter("LEGACY","tenant=CENTER");
        assertEquals(ReferralAwardIntakeService.Status.CANCEL_PENDING,f.base.call(null).status());
    }
    @Test void unknownAndLowerWatermarkNeverEraseCancellationOrCreateNewIntent() {
        f.state=State.CANCEL_REQUESTED;f.revision=3;f.cancel=3;f.sync();f.state=State.UNKNOWN;assertEquals(Status.CANCELLED,f.sync().observation().status());
        f.state=State.CONFIRMED;f.revision=2;f.cancel=0;var observed=f.sync().observation();assertEquals(Status.CANCELLED,observed.status());assertEquals(3,observed.cancelRevision());assertEquals("CANCEL_HELD",f.held());assertEquals(1,f.base.count("mk_referral_award_held_outbox"));
    }
    @Test void transportRefreshKeepsReceiptButDifferentPermanentReceiptQuarantines() {
        assertEquals(Status.CHECKED,f.review().observation().status());f.base.inputs.clock.now=f.now.plusSeconds(1);assertEquals(Status.CHECKED,f.review().observation().status());
        f.confirmationId="other";assertEquals(Status.QUARANTINED,f.sync().observation().status());assertEquals("CANCEL_HELD",f.held());
        f.confirmationId="confirmation";assertEquals(Status.QUARANTINED,f.review().observation().status());
    }
    @Test void riskRejectRemainsBlockedEvenAfterLaterAllow() {
        f.action=ReferralIntakeRiskPort.Action.REJECT;assertEquals(Status.BLOCKED,f.review().observation().status());f.action=ReferralIntakeRiskPort.Action.ALLOW;
        assertEquals(Status.BLOCKED,f.review().observation().status());assertNull(f.repo.find("tenant",f.base.request()).validUntil());assertEquals("HELD",f.held());
    }
    @Test void lastReviewWriteFailureRollsBackWatermarkAndObservation() {
        f.revision=3;f.failWrite=true;assertThrows(IllegalStateException.class,f::review);assertNull(f.repo.find("tenant",f.base.request()));
        assertEquals(2,f.base.contexts.find("tenant",f.base.request()).confirmation().currentRevision());assertEquals("HELD",f.held());
    }
    @Test void realPreparationLockWaitRechecksRawProofExpiryBeforeDurableChecked() throws Exception {
        CountDownLatch locked=new CountDownLatch(1);f.confirmLifetime=Duration.ofNanos(500);
        try(Connection blocker=ReferralPreparationMySqlTest.datasource.getConnection();var executor=Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);f.recoverHook=()->{try(var q=blocker.prepareStatement("SELECT state_version FROM mk_referral_award_preparation WHERE tenant_id='tenant' AND source_request_id=? FOR UPDATE")){q.setString(1,f.base.request());q.executeQuery().close();locked.countDown();}catch(Exception e){throw new IllegalStateException(e);}};
            var waiting=executor.submit(f::review);
            try{assertTrue(locked.await(30,TimeUnit.SECONDS));ReferralPreparationMySqlTest.awaitLockWait();assertFalse(waiting.isDone());f.base.inputs.clock.now=f.now.plusNanos(600);blocker.commit();
                var error=assertThrows(ExecutionException.class,()->waiting.get(15,TimeUnit.SECONDS));assertInstanceOf(com.acme.marketing.platform.error.ConflictException.class,error.getCause());
            }finally{blocker.rollback();}
        }
        assertNull(f.repo.find("tenant",f.base.request()));assertEquals("HELD",f.held());
    }
    static class Fixture {
        final ReferralAwardIntakeMySqlTest.Fixture base=new ReferralAwardIntakeMySqlTest.Fixture();final Instant now=ReferralAwardIntakeMySqlTest.NOW;
        final MybatisReferralHeldReviewRepository repo=new MybatisReferralHeldReviewRepository(mapper,base.contexts,new ObjectMapper());
        State state=State.CONFIRMED;long revision=2,cancel;String confirmationId="confirmation";int risks;boolean failWrite;
        ReferralIntakeRiskPort.Action action=ReferralIntakeRiskPort.Action.ALLOW;Duration riskLifetime=Duration.ofSeconds(3),confirmLifetime=Duration.ofSeconds(5);Runnable recoverHook=()->{};
        Fixture() throws Exception{base.call(base.token());}
        ReferralHeldReviewService service(){
            ReferralHeldReviewRepository controlled=new ReferralHeldReviewRepository(){
                public Observation record(ReferralIntakeIdentityPort.Binding b,Status s,long r,long c,Instant n,Instant u){var result=repo.record(b,s,r,c,n,u);if(failWrite)throw new IllegalStateException("injected write rollback");return result;}
                public Observation find(String t,String r){return repo.find(t,r);}
            };
            return new ReferralHeldReviewService(base.preparations,base.contexts,controlled,(scope,request)->{base.outside();return base.binding;},new ReferralIntakeConfirmationPort(){
                public Result confirm(com.acme.marketing.benefit.domain.ReferralAwardPreparation.Identity i){throw new AssertionError("no new confirm");}
                public Result recover(com.acme.marketing.benefit.domain.ReferralAwardPreparation.Identity i){base.outside();recoverHook.run();return state==State.UNKNOWN?null:new Result(i,state,revision,cancel,confirmationId,1,now,null,base.inputs.clock.instant(),base.inputs.clock.instant().plus(confirmLifetime));}
            },(b,p)->{base.outside();risks++;return new ReferralIntakeRiskPort.Decision(b.identity(),action,"risk-review",base.inputs.clock.instant(),base.inputs.clock.instant().plus(riskLifetime));},base.protection,base.inputs.modes,base.inputs.clock,new DataSourceTransactionManager(ReferralPreparationMySqlTest.datasource),true,Duration.ofSeconds(10));
        }
        ReferralHeldReviewService.View review(){return service().reviewBeforeDispatch(base.inputs.scope,base.request());}
        ReferralHeldReviewService.View sync(){return service().syncCancellation(base.inputs.scope,base.request());}
        String held(){return ReferralPreparationMySqlTest.jdbc.queryForObject("SELECT state_name FROM mk_referral_award_held_outbox WHERE tenant_id='tenant' AND source_request_id=?",String.class,base.request());}
    }
}
