package com.acme.marketing.benefit;

import com.acme.marketing.benefit.application.*;
import com.acme.marketing.benefit.application.ReferralPreparationRepository.*;
import com.acme.marketing.benefit.domain.ReferralAwardPreparation.*;
import com.acme.marketing.benefit.infrastructure.persistence.MybatisReferralPreparationRepository;
import com.acme.marketing.benefit.infrastructure.persistence.mapper.ReferralPreparationMapper;
import com.acme.marketing.contracts.referral.*;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/** 单次临时MySQL专项；仅手动装配必要Spring事务/MyBatis，无HTTP、worker、真实渠道或外部DB地址。 */
class ReferralPreparationMySqlTest {
    static final Instant NOW=Instant.parse("2026-09-08T00:00:00Z");
    static MySQLContainer mysql;static HikariDataSource datasource;static JdbcTemplate jdbc;static TransactionTemplate tx;
    static ReferralPreparationRepository repository;static MutableClock clock;
    Fixture fixture;
    @BeforeAll static void database() throws Exception {
        mysql=new MySQLContainer("mysql:8.4.11").withDatabaseName("referral_prepare_test").withUsername("test").withPassword("test")
                .withTmpFs(Map.of("/var/lib/mysql","rw")).withStartupTimeoutSeconds(300).withStartupTimeout(Duration.ofMinutes(5))
                .withCommand("--performance-schema=ON","--innodb-lock-wait-timeout=15");
        mysql.start();
        var configuration=new HikariConfig();configuration.setJdbcUrl(mysql.getJdbcUrl());configuration.addDataSourceProperty("connectionTimeZone","UTC");configuration.setUsername(mysql.getUsername());configuration.setPassword(mysql.getPassword());configuration.setMaximumPoolSize(8);
        datasource=new HikariDataSource(configuration);jdbc=new JdbcTemplate(datasource);
        Flyway.configure().dataSource(datasource).locations("classpath:db/migration").load().migrate();
        tx=new TransactionTemplate(new DataSourceTransactionManager(datasource));
        var factory=new SqlSessionFactoryBean();factory.setDataSource(datasource);
        var mybatis=new org.apache.ibatis.session.Configuration();mybatis.setLocalCacheScope(org.apache.ibatis.session.LocalCacheScope.STATEMENT);
        factory.setConfiguration(mybatis);factory.setMapperLocations(new ClassPathResource("mapper/ReferralPreparationMapper.xml"));
        clock=new MutableClock();repository=new MybatisReferralPreparationRepository(new SqlSessionTemplate(factory.getObject()).getMapper(ReferralPreparationMapper.class),clock);
    }
    @AfterAll static void close(){if(datasource!=null)datasource.close();if(mysql!=null)mysql.stop();}
    @BeforeEach void inputs() throws Exception{clock.now.set(NOW);fixture=new Fixture();}

    @Test void migrationKeepsEveryTableColumnCommentAndAllOlderVersions() {
        assertEquals(10,jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1 AND type='SQL' AND CAST(version AS UNSIGNED) BETWEEN 1 AND 10",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='mk_referral_award_preparation' AND column_comment=''",Integer.class));
        assertFalse(jdbc.queryForObject("SELECT table_comment FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='mk_referral_award_preparation'",String.class).isBlank());
    }
    @Test void duplicatePreparationKeepsOriginalCipherAndExactNanosecondLease() {
        Instant until=NOW.plusSeconds(30).plusNanos(500);var original=prepare(until);
        var differentCipher=fixture.protection.protect(fixture.id,fixture.candidate);
        var replay=tx.execute(s->repository.prepare(fixture.id,differentCipher,"other",NOW.plusSeconds(50)));
        assertArrayEquals(original.snapshot().encrypted().ciphertext(),replay.snapshot().encrypted().ciphertext());
        var stored=repository.find("tenant",fixture.id.sourceRequestId()).orElseThrow();assertEquals(until,stored.state().lease().expiresAt());
        assertEquals(new ObjectMapper().writeValueAsString(fixture.candidate.intent()),fixture.protection.restore(fixture.id,stored.snapshot()).payload());
        assertFalse(new String(stored.snapshot().encrypted().ciphertext(),java.nio.charset.StandardCharsets.UTF_8).contains("用户"));
        assertEquals(1,count());
    }
    @Test void changedIdentityCannotOverwriteOriginalSnapshotOrState() {
        var original=prepare(NOW.plusSeconds(30));
        var changed=new Identity("tenant",fixture.id.sourceSystem(),fixture.id.sourceRequestId(),fixture.id.rewardId(),fixture.id.stableClaimsDigest(),fixture.id.payloadHash(),2);
        var transplanted=new ReferralCandidateSnapshotService.Snapshot(ReferralCandidateSnapshotService.binding(changed),original.snapshot().encrypted());
        assertThrows(IllegalStateException.class,()->tx.execute(s->repository.prepare(changed,transplanted,"worker",NOW.plusSeconds(30))));
        assertEquals(original.state(),repository.find("tenant",fixture.id.sourceRequestId()).orElseThrow().state());
    }
    @Test void unknownLeaseTakeoverRetainsConfirmationIdentityAndRejectsOldFence() {
        prepare(NOW.plusNanos(500));apply(new Begin(new Owner("worker",1),fixture.admission(NOW)));
        apply(new Unknown(new Owner("worker",1)));clock.now.set(NOW.plusNanos(500));
        var taken=apply(new TakeOver("recovery",NOW.plusSeconds(30)));
        assertEquals(Phase.CONFIRM_UNKNOWN,taken.state().phase());assertEquals(2,taken.state().lease().fence());
        assertThrows(IllegalStateException.class,()->apply(new Unknown(new Owner("worker",1))));
        var confirmed=apply(new Confirm(new Owner("recovery",2),new Receipt(fixture.id,"confirmation",NOW.plusNanos(499))));
        assertEquals(NOW.plusNanos(499),confirmed.state().receipt().confirmedAt());
    }
    @Test void differentNanosecondContentOfSameReceiptPersistsStickyQuarantine() {
        prepare(NOW.plusSeconds(30));apply(new Begin(new Owner("worker",1),fixture.admission(NOW)));clock.now.set(NOW.plusSeconds(1));
        apply(new Confirm(new Owner("worker",1),new Receipt(fixture.id,"same-confirmation",NOW.plusNanos(500))));
        var isolated=apply(new Confirm(new Owner("worker",1),new Receipt(fixture.id,"same-confirmation",NOW.plusNanos(600))));
        assertTrue(isolated.state().quarantined());assertEquals(NOW.plusNanos(500),isolated.state().receipt().confirmedAt());
        assertTrue(repository.find("tenant",fixture.id.sourceRequestId()).orElseThrow().state().quarantined());
        assertThrows(IllegalStateException.class,()->apply(new Accept(new Owner("worker",1),"intent",fixture.admission(NOW))));
    }
    @Test void remoteReceiptLocalRollbackCanRecoverSamePreparationAfterOriginalTokenExpiry() {
        prepare(NOW.plusSeconds(10));apply(new Begin(new Owner("worker",1),fixture.admission(NOW)));clock.now.set(NOW.plusSeconds(1));
        var receipt=new Receipt(fixture.id,"permanent-confirmation",NOW);
        assertThrows(IllegalStateException.class,()->tx.execute(s->{repository.apply(fixture.id,new Confirm(new Owner("worker",1),receipt));throw new IllegalStateException("simulated crash before commit");}));
        assertEquals(Phase.CONFIRMING,repository.find("tenant",fixture.id.sourceRequestId()).orElseThrow().state().phase());
        clock.now.set(NOW.plusSeconds(10));apply(new TakeOver("recovery",NOW.plusSeconds(30)));
        apply(new Confirm(new Owner("recovery",2),receipt));
        var accepted=apply(new Accept(new Owner("recovery",2),"original-intent",fixture.admission(NOW.plusSeconds(10))));
        assertEquals("original-intent",accepted.state().replay(fixture.id));assertEquals(1,count());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM mk_award_intent_outbox",Integer.class));
        // 此仓储专项只落准备引用，没有接可投递intent/outbox；生产服务仍须原子插入它们。
    }
    @Test void parallelFirstPreparationHasOnePermanentRowAndOneFrozenCipher() throws Exception {
        var another=fixture.protection.protect(fixture.id,fixture.candidate);CountDownLatch start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var a=executor.submit(()->{start.await();return tx.execute(s->repository.prepare(fixture.id,fixture.snapshot,"worker",NOW.plusSeconds(30)));});
            var b=executor.submit(()->{start.await();return tx.execute(s->repository.prepare(fixture.id,another,"worker-2",NOW.plusSeconds(30)));});
            start.countDown();var first=a.get(20,TimeUnit.SECONDS);var second=b.get(20,TimeUnit.SECONDS);
            assertArrayEquals(first.snapshot().encrypted().ciphertext(),second.snapshot().encrypted().ciphertext());assertEquals(1,count());
        }
    }
    @Test void realRowLockWaitRechecksRawLeaseDeadline() throws Exception {
        prepare(NOW.plusNanos(500));
        try(Connection blocker=datasource.getConnection();var executor=Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);
            try(var lock=blocker.prepareStatement("SELECT state_version FROM mk_referral_award_preparation WHERE tenant_id='tenant' AND source_request_id=? FOR UPDATE")) {
                lock.setString(1,fixture.id.sourceRequestId());lock.executeQuery().close();
            }
            var waiting=executor.submit(()->apply(new Begin(new Owner("worker",1),fixture.admission(NOW))));
            try {
                awaitLockWait();assertFalse(waiting.isDone());clock.now.set(NOW.plusNanos(600));blocker.commit();
                var failure=assertThrows(ExecutionException.class,()->waiting.get(10,TimeUnit.SECONDS));
                assertInstanceOf(IllegalStateException.class,failure.getCause());
                assertEquals("referral preparation transition rejected",failure.getCause().getMessage());
            } finally {blocker.rollback();}
        }
        assertEquals(Phase.PREPARED,repository.find("tenant",fixture.id.sourceRequestId()).orElseThrow().state().phase());
    }

    Stored prepare(Instant until){return tx.execute(s->repository.prepare(fixture.id,fixture.snapshot,"worker",until));}
    Stored apply(Command command){return tx.execute(s->repository.apply(fixture.id,command));}
    int count(){return jdbc.queryForObject("SELECT COUNT(*) FROM mk_referral_award_preparation WHERE tenant_id='tenant' AND source_request_id=?",Integer.class,fixture.id.sourceRequestId());}
    static void awaitLockWait() throws Exception {
        try(Connection c=DriverManager.getConnection(mysql.getJdbcUrl(),"root",mysql.getPassword());var query=c.prepareStatement("SELECT COUNT(*) FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID WHERE l.OBJECT_SCHEMA=? AND l.OBJECT_NAME='mk_referral_award_preparation'")) {
            query.setString(1,mysql.getDatabaseName());long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            do {try(var rows=query.executeQuery()){rows.next();if(rows.getLong(1)>0)return;}Thread.sleep(25);}while(System.nanoTime()<deadline);
            throw new AssertionError("no actual preparation row lock wait observed");
        }
    }
    static class Fixture {
        final ReferralAwardIntentAssembler.Candidate candidate;final Identity id;
        final ReferralCandidateSnapshotService protection;final ReferralCandidateSnapshotService.Snapshot snapshot;
        Fixture() throws Exception {
            var f=new ReferralAwardIntentAssemblerTest.Fixture();var c=f.claims;String reward="reward-"+UUID.randomUUID();
            f.claims=new ReferralAwardAuthorizationClaims(c.issuer(),c.audience(),c.tenantId(),c.organizationId(),c.shopId(),reward,ReferralAwardIdentity.sourceRequestId("tenant",reward),c.campaignId(),c.definitionId(),c.definitionVersion(),c.generation(),c.artifactId(),c.artifactHash(),c.participantId(),c.relationId(),c.milestone(),c.beneficiarySubject(),c.role(),c.ruleId(),c.quantity(),c.qualificationRevision(),c.issuedAt(),c.expiresAt());
            candidate=f.assemble();id=ReferralCandidateSnapshotTest.identity(candidate);
            protection=new ReferralCandidateSnapshotService(new ReferralCandidateSnapshotTest.ProtectionFixture(),new ObjectMapper());snapshot=protection.protect(id,candidate);
        }
        Admission admission(Instant now){return new Admission(id,true,"fixture-risk",now,now.plusSeconds(20),NOW,NOW.plusSeconds(5));}
    }
    static class MutableClock extends Clock {
        final AtomicReference<Instant> now=new AtomicReference<>(NOW);@Override public Instant instant(){return now.get();}
        @Override public ZoneId getZone(){return ZoneOffset.UTC;}@Override public Clock withZone(ZoneId zone){return this;}
    }
}
