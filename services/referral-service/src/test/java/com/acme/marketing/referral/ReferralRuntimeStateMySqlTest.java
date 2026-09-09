package com.acme.marketing.referral;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.marketing.contracts.artifact.ArtifactAttestation;
import com.acme.marketing.contracts.release.*;
import com.acme.marketing.platform.crypto.Digests;
import com.acme.marketing.platform.crypto.Ed25519;
import com.acme.marketing.platform.identity.*;
import com.acme.marketing.referral.application.release.*;
import com.acme.marketing.referral.application.release.ReferralRuntimeRepository.*;
import java.security.KeyPair;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/** 本地机制使用真实签名/隔离库；Ready 夹具只用于故障场景，不表示闭包已接通。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "marketing.security.mode=DEV", "marketing.security.dev-headers-enabled=false"})
class ReferralRuntimeStateMySqlTest {
    static final Instant NOW = Instant.parse("2026-09-09T00:00:00Z");
    static final KeyPair KEYS = Ed25519.generateKeyPair();
    static final JsonMapper JSON = JsonMapper.builder().build();
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) { ReferralParticipationIntegrationTest.database(registry); }
    @Autowired ReferralRuntimeRepository repository;
    @Autowired ReferralReleaseRepository releases;
    @Autowired PlatformTransactionManager manager;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.acme.marketing.referral.application.ReferralParticipationPermitPort permits;
    String tenant;
    final AtomicReference<Instant> time = new AtomicReference<>(NOW);
    final Clock clock = new Clock() {
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return time.get(); }
    };
    @BeforeEach void prepare() { tenant="runtime-"+UUID.randomUUID(); time.set(NOW); }
    ReferralReleaseVerifier verifier() { return new ReferralReleaseVerifier(Map.of("key",KEYS.getPublic()),Map.of("key",KEYS.getPublic()),"test","cell","default"); }
    ReferralRuntimeReadinessPort ready() {
        return (tenant, manifest) -> new ReferralRuntimeReadinessPort.Proof(tenant,manifest.manifestId(),manifest.generation(),manifest.signature(),time.get(),time.get().plusSeconds(5));
    }
    ReferralRuntimeStateService service(ReferralRuntimeRepository repo, ReferralRuntimeReadinessPort ready) {
        return new ReferralRuntimeStateService(repo,verifier(),new ReferralRuntimeDirectiveVerifier(Map.of("key",KEYS.getPublic()),"test","cell","default"),ready,clock,manager,"test","cell","default",Duration.ofSeconds(10));
    }
    ReferralRuntimeStateService service() { return service(repository,ready()); }
    TenantScope scope() { return new TenantScope(new TenantId(tenant),Set.of("org"),Set.of("shop"),"runtime",Set.of("runtime:warm","runtime:activate","runtime:kill-switch","runtime:read")); }
    Key activeKey() { return new Key(tenant,"ACTIVATION","test","cell","default"); }
    Key killKey() { return new Key(tenant,"KILL","","","default"); }
    byte[] payload() {
        return JSON.writeValueAsBytes(new ReferralPlanCompiler.CompiledReferralPlan("definition",
            new ReferralPlanCompiler.Scope("org","shop"),new ReferralPlanCompiler.BindingPolicy(ReferralPlanCompiler.Attribution.FIRST_VALID_BIND,600,ReferralPlanCompiler.InviteeScope.NEW_CUSTOMER),
            new ReferralPlan(NOW,NOW.plusSeconds(3600),NOW.plusSeconds(7200),600,0,0,ReferralPlan.GoalType.FIRST_ORDER_SETTLED,100,"CNY",
                List.of(new ReferralRewardRule("rule",ReferralRewardRule.Role.INVITER,ReferralRewardRule.Mode.PER_RELATION,1,"benefit-v1","sku-v1",1,1,100)))));
    }
    ReleaseManifest install(long generation) {
        String checksum="sha256:"+Digests.sha256Hex(payload()); String source="sha256:"+"a".repeat(64);
        String sig=ArtifactAttestation.sign(KEYS.getPrivate(),tenant,"artifact-"+generation,"definition",generation,"REFERRAL_PLAN","marketing-referral-plan/1",checksum,source);
        var artifact=new ArtifactReference("artifact-"+generation,"REFERRAL_PLAN","artifact://local",checksum,source,"key",sig,"marketing-referral-plan/1","definition",generation);
        var manifest=new ReleaseManifestSigner().sign(new ReleaseManifest("manifest-"+generation,new TenantId(tenant),"test","cell","referral","default",generation,0,List.of(),List.of(),List.of(artifact),Map.of(),0,NOW,NOW.plusSeconds(600),"admin",List.of("approval"),NOW,""),KEYS.getPrivate());
        new ReferralReleaseInstallationService(verifier(),releases,clock,manager).install(scope(),"key",manifest,payload());
        return manifest;
    }
    ActivationDirective activation(ReleaseManifest m,long sequence) {
        return ActivationDirectiveSigner.sign(KEYS.getPrivate(),new ActivationDirective("activate-"+sequence,new TenantId(tenant),m.manifestId(),"test","cell","referral","default",sequence,m.generation(),m.generation(),0,m.signature(),NOW,NOW.plusSeconds(300),"admin","key",""));
    }
    KillSwitchDirective kill(long sequence,boolean enabled) {
        return KillSwitchDirectiveSigner.sign(KEYS.getPrivate(),new KillSwitchDirective("kill-"+sequence,new TenantId(tenant),"default",sequence,enabled,"test",time.get(),"admin","key",""));
    }
    int audit(String kind) { return jdbc.queryForObject("SELECT COUNT(*) FROM mk_referral_runtime_directive WHERE tenant_id=? AND stream_kind=?",Integer.class,tenant,kind); }
    @Test void unavailableReadyCannotActivateVerifiedGeneration() {
        var m=install(1); var svc=service(repository,ReferralRuntimeReadinessPort.unavailable());
        assertThrows(IllegalArgumentException.class,()->svc.activate(scope(),activation(m,1)));
        assertNull(repository.current(activeKey())); assertEquals(0,audit("ACTIVATION"));
    }
    @Test void activationIsDurableIdempotentAndStillNotParticipationPermit() {
        var a=activation(install(1),1); assertEquals(1,service().activate(scope(),a));
        assertEquals(1,service().activate(scope(),a)); assertEquals(1,audit("ACTIVATION"));
        assertFalse(service().inspect(scope()).locallyEligible());
        service().applyKill(scope(),kill(1,false));
        assertTrue(service().inspect(scope()).locallyEligible());
        assertEquals(1,service().inspect(scope()).generation());
        assertNull(permits.current(tenant,"campaign","org","shop"));
    }
    @Test void rollbackRequiresHigherSequenceAndRetainedInstalledGeneration() {
        var old=install(1); var latest=install(2); var svc=service();
        svc.activate(scope(),activation(old,10)); svc.activate(scope(),activation(latest,11));
        assertThrows(IllegalArgumentException.class,()->svc.activate(scope(),activation(old,9)));
        svc.activate(scope(),activation(old,12)); svc.applyKill(scope(),kill(1,false));
        assertEquals(12,service().inspect(scope()).activationSequence()); assertEquals(1,service().inspect(scope()).generation());
        assertEquals(3,audit("ACTIVATION"));
    }
    @Test void sameSequenceDifferentActivationConflicts() {
        var m1=install(1); var m2=install(2); service().activate(scope(),activation(m1,1));
        assertThrows(IllegalArgumentException.class,()->service().activate(scope(),activation(m2,1)));
        assertEquals(1,audit("ACTIVATION"));
    }
    @Test void sameSequenceDifferentKillConflictsAndOlderClearCannotResume() {
        service().applyKill(scope(),kill(2,true));
        assertThrows(IllegalArgumentException.class,()->service().applyKill(scope(),kill(2,false)));
        assertThrows(IllegalArgumentException.class,()->service().applyKill(scope(),kill(1,false)));
        assertEquals(2,repository.current(killKey()).sequence()); assertEquals(1,audit("KILL"));
    }
    @Test void replayDoesNotRefreshClearAndNewSignedClearResumesLocalCheck() {
        service().activate(scope(),activation(install(1),1)); var clear=kill(1,false); service().applyKill(scope(),clear);
        time.set(NOW.plusSeconds(9)); service().applyKill(scope(),clear); assertTrue(service().inspect(scope()).locallyEligible());
        assertEquals(NOW.plusSeconds(10),service().inspect(scope()).expiresAt());
        time.set(NOW.plusSeconds(10)); service().applyKill(scope(),clear); assertFalse(service().inspect(scope()).locallyEligible());
        service().applyKill(scope(),kill(2,false)); assertTrue(service().inspect(scope()).locallyEligible());
        assertEquals(2,audit("KILL"));
    }
    @Test void enabledKillRemainsBlockingAndReadyLossAlsoBlocks() {
        service().activate(scope(),activation(install(1),1)); service().applyKill(scope(),kill(1,true));
        assertFalse(service().inspect(scope()).locallyEligible()); time.set(NOW.plusSeconds(30));
        assertFalse(service().inspect(scope()).locallyEligible()); service().applyKill(scope(),kill(2,false));
        assertFalse(service(repository,ReferralRuntimeReadinessPort.unavailable()).inspect(scope()).locallyEligible());
    }
    @Test void expiredActivationRejectsReplayAndGuard() {
        var a=activation(install(1),1); service().activate(scope(),a); time.set(a.expiresAt()); service().applyKill(scope(),kill(1,false));
        assertFalse(service().inspect(scope()).locallyEligible()); assertThrows(IllegalArgumentException.class,()->service().activate(scope(),a));
        assertEquals(1,audit("ACTIVATION"));
    }
    @Test void missingGenerationAndScopeMismatchCannotWrite() {
        var m=install(1); String original=tenant; tenant="other-"+UUID.randomUUID();
        assertThrows(IllegalArgumentException.class,()->service().activate(scope(),activation(m,1)));
        tenant=original; var denied=new TenantScope(new TenantId(tenant),Set.of("wrong"),Set.of("shop"),"actor",Set.of("runtime:activate"));
        assertThrows(RuntimeException.class,()->service().activate(denied,activation(m,1))); assertEquals(0,audit("ACTIVATION"));
        assertThrows(RuntimeException.class,()->service().applyKill(new TenantScope(new TenantId(tenant),Set.of(),Set.of(),"actor"),kill(1,true)));
    }
    @Test void readinessMustBindManifestAndHaveBoundedLifetime() {
        var m=install(1); var a=activation(m,1);
        for (var proof:List.of(
            new ReferralRuntimeReadinessPort.Proof(tenant,m.manifestId(),2,m.signature(),NOW,NOW.plusSeconds(5)),
            new ReferralRuntimeReadinessPort.Proof(tenant,m.manifestId(),1,"wrong",NOW,NOW.plusSeconds(5)),
            new ReferralRuntimeReadinessPort.Proof(tenant,m.manifestId(),1,m.signature(),NOW,NOW.plusSeconds(11)),
            new ReferralRuntimeReadinessPort.Proof(tenant,m.manifestId(),1,m.signature(),NOW.minusSeconds(1),NOW))) {
            assertThrows(IllegalArgumentException.class,()->service(repository,(t,x)->proof).activate(scope(),a));
        }
        assertEquals(0,audit("ACTIVATION"));
    }
    @Test void failureAfterAdvancingRollsBackCursorAndAudit() {
        var m=install(1); var delegate=repository;
        var faulty=new Delegating(delegate) { @Override public void advance(Key k,long old,long next,String json,Instant now) {
            super.advance(k,old,next,json,now); throw new IllegalStateException("fault");
        }};
        assertThrows(IllegalStateException.class,()->service(faulty,ready()).activate(scope(),activation(m,1)));
        assertNull(repository.current(activeKey())); assertEquals(0,audit("ACTIVATION"));
    }
    @Test void expiryWhileWaitingOnLockRollsBackEmptyCursor() {
        var m=install(1); var a=activation(m,1); var delegate=repository;
        var waiting=new Delegating(delegate) { @Override public Cursor lock(Key k) { var c=super.lock(k); time.set(NOW.plusSeconds(5)); return c; }};
        assertThrows(IllegalArgumentException.class,()->service(waiting,ready()).activate(scope(),a));
        assertNull(repository.current(activeKey())); assertEquals(0,audit("ACTIVATION"));
    }
    @Test void concurrentIdenticalActivationHasSingleAudit() throws Exception {
        var a=activation(install(1),1); var svc=service(); var scope=scope(); var start=new CountDownLatch(1);
        try (var pool=Executors.newFixedThreadPool(2)) {
            var one=pool.submit(()->{start.await();return svc.activate(scope,a);});
            var two=pool.submit(()->{start.await();return svc.activate(scope,a);}); start.countDown();
            assertEquals(one.get(30,TimeUnit.SECONDS),two.get(30,TimeUnit.SECONDS));
        }
        assertEquals(1,audit("ACTIVATION"));
    }
    @Test void concurrentOutOfOrderActivationEndsAtHighestSequence() throws Exception {
        var m=install(1); var low=activation(m,1); var high=activation(m,2); var scope=scope(); var svc=service(); var start=new CountDownLatch(1);
        try (var pool=Executors.newFixedThreadPool(2)) {
            var one=pool.submit(()->{start.await();try{return svc.activate(scope,low);}catch(IllegalArgumentException e){assertEquals("REFERRAL_ACTIVATION_STALE",e.getMessage());return 0L;}});
            var two=pool.submit(()->{start.await();return svc.activate(scope,high);}); start.countDown(); one.get(30,TimeUnit.SECONDS); assertEquals(2,two.get(30,TimeUnit.SECONDS));
        }
        assertEquals(2,repository.current(activeKey()).sequence());
    }
    @Test void concurrentSameSequenceDifferentKillHasOneWinner() throws Exception {
        var enabled=kill(1,true); var disabled=kill(1,false); var scope=scope(); var svc=service(); var start=new CountDownLatch(1);
        try (var pool=Executors.newFixedThreadPool(2)) {
            var one=pool.submit(()->{start.await();try{svc.applyKill(scope,enabled);return true;}catch(IllegalArgumentException e){assertEquals("REFERRAL_KILL_CONFLICT",e.getMessage());return false;}});
            var two=pool.submit(()->{start.await();try{svc.applyKill(scope,disabled);return true;}catch(IllegalArgumentException e){assertEquals("REFERRAL_KILL_CONFLICT",e.getMessage());return false;}});
            start.countDown(); assertNotEquals(one.get(30,TimeUnit.SECONDS),two.get(30,TimeUnit.SECONDS));
        }
        assertEquals(1,audit("KILL"));
    }
    @Test void corruptedPersistedSignatureFailsClosedAfterReconstruction() {
        service().activate(scope(),activation(install(1),1)); service().applyKill(scope(),kill(1,false));
        assertTrue(service().inspect(scope()).locallyEligible());
        var current=repository.current(activeKey());
        var node=JSON.<tools.jackson.databind.node.ObjectNode>valueToTree(JSON.readValue(current.directiveJson(),ActivationDirective.class));
        node.put("signature","corrupt");
        jdbc.update("UPDATE mk_referral_runtime_cursor SET directive_json=? WHERE tenant_id=? AND stream_kind='ACTIVATION'",JSON.writeValueAsString(node),tenant);
        assertFalse(service().inspect(scope()).locallyEligible());
    }
    @Test void namespaceMismatchBetweenVerifierAndStoreCannotWrite() {
        var configured=new ReferralRuntimeStateService(repository,verifier(),new ReferralRuntimeDirectiveVerifier(Map.of("key",KEYS.getPublic()),"test","cell","default"),
            ready(),clock,manager,"test","cell","other",Duration.ofSeconds(10));
        assertThrows(IllegalArgumentException.class,()->configured.applyKill(scope(),kill(1,false)));
        assertEquals(0,audit("KILL"));
    }
    @Test void independentJvmRecoversSignedCursorAndHistoricalArtifact() throws Exception {
        var old=install(1); var next=install(2); service().activate(scope(),activation(next,8)); service().activate(scope(),activation(old,9));
        var mysql=ReferralParticipationIntegrationTest.MYSQL;
        var input=new ReferralRuntimeRestartProbe.Input(mysql.getJdbcUrl(),mysql.getUsername(),mysql.getPassword(),tenant,
            Base64.getEncoder().encodeToString(KEYS.getPublic().getEncoded()),NOW,9,1);
        var output=java.nio.file.Files.createTempFile("referral-r3-restart-", ".log");
        Process process=new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"),"bin","java").toString(),
            "-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),ReferralRuntimeRestartProbe.class.getName())
            .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            try(var stdin=process.getOutputStream()){stdin.write(JSON.writeValueAsBytes(input));}
            assertTrue(process.waitFor(30,TimeUnit.SECONDS),"restart probe timeout");
            assertEquals(0,process.exitValue(),java.nio.file.Files.readString(output));
            assertTrue(java.nio.file.Files.readString(output).contains("RESTORED 9 1"));
        } finally { if(process.isAlive())process.destroyForcibly(); }
        assertThrows(IllegalArgumentException.class,()->service().activate(scope(),activation(next,8)));
    }
    @Test void badSignedContentAndWrongScopeDoNotCreateCursor() {
        var a=activation(install(1),1); var node=JSON.<tools.jackson.databind.node.ObjectNode>valueToTree(a);
        node.put("activationSequence",99);
        assertThrows(IllegalArgumentException.class,()->service().activate(scope(),JSON.treeToValue(node,ActivationDirective.class)));
        var k=kill(1,false); var altered=JSON.<tools.jackson.databind.node.ObjectNode>valueToTree(k); altered.put("enabled",true);
        assertThrows(IllegalArgumentException.class,()->service().applyKill(scope(),JSON.treeToValue(altered,KillSwitchDirective.class)));
        assertNull(repository.current(activeKey())); assertNull(repository.current(killKey()));
    }
    @Test void metadataCommentsAndReconstructionPreserveMonotonicCursor() {
        service().applyKill(scope(),kill(8,true)); assertEquals(8,repository.current(killKey()).sequence());
        assertThrows(IllegalArgumentException.class,()->service().applyKill(scope(),kill(7,false)));
        assertEquals(15,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name IN ('mk_referral_runtime_cursor','mk_referral_runtime_directive') AND column_comment<>''",Integer.class));
    }
    static class Delegating implements ReferralRuntimeRepository {
        final ReferralRuntimeRepository delegate; Delegating(ReferralRuntimeRepository delegate){this.delegate=delegate;}
        public ReferralReleaseRepository.Stored installed(Key k,long g){return delegate.installed(k,g);}
        public Cursor lock(Key k){return delegate.lock(k);}
        public Cursor current(Key k){return delegate.current(k);}
        public void advance(Key k,long old,long next,String json,Instant now){delegate.advance(k,old,next,json,now);}
    }
}
