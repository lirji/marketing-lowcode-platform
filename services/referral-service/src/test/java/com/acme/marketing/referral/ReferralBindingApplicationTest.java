package com.acme.marketing.referral;
import static com.acme.marketing.referral.ReferralBindingTimingTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.acme.marketing.platform.crypto.Digests;
import com.acme.marketing.platform.error.ConflictException;
import com.acme.marketing.platform.identity.*;
import com.acme.marketing.referral.application.*;
import com.acme.marketing.referral.application.ReferralBindingService.Bind;
import com.acme.marketing.referral.application.ReferralBindingRepository.*;
import com.acme.marketing.referral.application.ReferralInviteRepository.*;
import com.acme.marketing.referral.application.TrustedReferralSubjectPort.*;
import com.acme.marketing.referral.domain.ReferralRelation;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import tools.jackson.databind.ObjectMapper;
/** 可控仓储与Clock验证纯应用边界；真实行锁/事务原子性另由隔离MySQL证明。 */
class ReferralBindingApplicationTest {
    static final String INVITER="a".repeat(64),INVITEE="c".repeat(64),TOKEN="A".repeat(43);
    @Test void roleLocksUseDeterministicOrderAndBoundNeverClaimsQualification() {
        var f=new Fixture();var result=f.service.bind(f.scope,f.request());
        assertEquals("BOUND",result.state());assertEquals(NOW.plusSeconds(30),result.qualifyDeadline());
        var order=inOrder(f.repository,f.invites,f.tokens);
        order.verify(f.repository).lockInviter("tenant","campaign",INVITER);
        order.verify(f.repository).lockInvitee(eq("tenant"),eq("campaign"),any(),any());
        order.verify(f.invites).participant("tenant","participant",true);order.verify(f.tokens).lock("tenant",Digests.sha256Hex(TOKEN));
        verify(f.repository).save(any(),any(),eq("bff-machine"),eq("trace"),argThat(s->s.contains("PENDING")&&!s.contains(INVITEE)&&!s.contains(TOKEN)),anyString());
    }
    @Test void finalRelationLockReadCannotExtendIdentityOrPermit() {
        for(long delayedSeconds:new long[]{10,61}) {
            var f=new Fixture();when(f.repository.relation(anyString(),anyString(),anyString())).thenAnswer(a->{f.clock.now=NOW.plusSeconds(delayedSeconds);return null;});
            assertThrows(ConflictException.class,()->f.service.bind(f.scope,f.request()));verify(f.repository,never()).save(any(),any(),anyString(),anyString(),anyString(),anyString());
            verify(f.repository,never()).complete(anyString(),anyString(),anyString(),anyString(),any());
        }
    }
    @Test void priorSuccessfulCommandReplaysAfterTokenAndPermitExpireDuringLock() {
        var f=new Fixture();var original=f.original();
        when(f.repository.lockCommand(anyString(),anyString(),anyString(),anyString(),any())).thenAnswer(a->{f.clock.now=NOW.plusSeconds(200);return new Command(a.getArgument(3),original.relationId());});
        when(f.repository.relationById("tenant",original.relationId())).thenReturn(original);
        assertEquals(original,f.service.bind(f.scope,f.request()));verify(f.tokens,never()).lock(anyString(),anyString());verify(f.repository,never()).lockInviter(anyString(),anyString(),anyString());
    }
    @Test void newKeyForPriorRelationStillRequiresCurrentIdentityAndOriginalToken() {
        var f=new Fixture();var original=f.original();
        when(f.repository.lockInvitee(anyString(),anyString(),any(),any())).thenReturn(new Role("INVITEE",null,original.relationId(),1));
        when(f.repository.relation(anyString(),anyString(),anyString())).thenReturn(original);
        assertEquals(original,f.service.bind(f.scope,f.request()));verify(f.repository,never()).save(any(),any(),anyString(),anyString(),anyString(),anyString());
        var g=new Fixture();when(g.repository.lockInvitee(anyString(),anyString(),any(),any())).thenReturn(new Role("INVITEE",null,original.relationId(),1));
        when(g.repository.relation(anyString(),anyString(),anyString())).thenAnswer(a->{g.clock.now=NOW.plusSeconds(61);return original;});
        assertThrows(ConflictException.class,()->g.service.bind(g.scope,g.request()));verify(g.repository,never()).complete(anyString(),anyString(),anyString(),anyString(),any());
    }
    @Test void changedInputSourceFailureAndOuterTransactionNeverReachWrites() {
        var f=new Fixture();doThrow(new IllegalStateException("private-subject at internal.host")).when(f.subjects).resolve(any(),anyString(),any());
        var error=assertThrows(ConflictException.class,()->f.service.bind(f.scope,f.request()));assertNull(error.getCause());assertFalse(error.toString().contains("private-subject"));verifyNoInteractions(f.repository);
        var g=new Fixture();when(g.repository.lockCommand(anyString(),anyString(),anyString(),anyString(),any())).thenReturn(new Command("c".repeat(64),null));
        assertEquals("IDEMPOTENCY_PAYLOAD_CONFLICT",assertThrows(ConflictException.class,()->g.service.bind(g.scope,g.request())).code());verify(g.repository,never()).lockInviter(anyString(),anyString(),anyString());
        var outer=new Fixture();TransactionSynchronizationManager.setActualTransactionActive(true);
        try {assertThrows(ConflictException.class,()->outer.service.bind(outer.scope,outer.request()));}finally{TransactionSynchronizationManager.setActualTransactionActive(false);}
        verifyNoInteractions(outer.subjects,outer.repository,outer.tokens);
    }
    @Test void reversedHashOrderStillLocksInviteeBeforeInviterWithoutRoleConversion() {
        var f=new Fixture();String higherInviter="f".repeat(64);
        when(f.invites.participant(anyString(),anyString(),anyBoolean())).thenReturn(new Owned(PARTICIPANT,higherInviter,1));
        assertEquals("BOUND",f.service.bind(f.scope,f.request()).state());
        var order=inOrder(f.repository);order.verify(f.repository).lockInvitee(eq("tenant"),eq("campaign"),any(),any());
        order.verify(f.repository).lockInviter("tenant","campaign",higherInviter);
    }
    @Test void tokenExpiresAfterRelationReadOrIsRevokedBeforeFinalCheck() {
        for(boolean revoked:new boolean[]{false,true}) {
            var f=new Fixture();var token=new Token("token-id","participant",Digests.sha256Hex(TOKEN),NOW.plusSeconds(5),revoked?NOW:null);
            when(f.tokens.lock(anyString(),anyString())).thenReturn(token);
            if(!revoked)when(f.repository.relation(anyString(),anyString(),anyString())).thenAnswer(a->{f.clock.now=NOW.plusSeconds(5);return null;});
            assertEquals("REFERRAL_TOKEN_INVALID",assertThrows(ConflictException.class,()->f.service.bind(f.scope,f.request())).code());
            verify(f.repository,never()).save(any(),any(),anyString(),anyString(),anyString(),anyString());
        }
    }
    @Test void wrongSubjectBindingAndIndexAnchorFailBeforeAnyRoleWrite() {
        var f=new Fixture();doAnswer(a->{
            RequestBinding b=a.getArgument(2);var wrong=new RequestBinding(b.tenantId(),b.campaignId(),b.organizationId(),b.shopId(),b.operation(),b.idempotencyKey(),"f".repeat(64),b.method(),b.path());
            return new Subject("tenant",INVITEE,1,new byte[32],"test-key",wrong,NOW,NOW.plusSeconds(60));
        }).when(f.subjects).resolve(any(),anyString(),any());
        assertThrows(ConflictException.class,()->f.service.bind(f.scope,f.request()));verifyNoInteractions(f.repository);
        var g=new Fixture();when(g.participants.subjectIndexVersion("tenant")).thenReturn(2L);
        assertThrows(ConflictException.class,()->g.service.bind(g.scope,g.request()));verifyNoInteractions(g.repository);
    }
    @Test void nanosecondExpiryCannotBeReopenedByMicrosecondPersistenceRounding() {
        for(String boundary:new String[]{"subject","permit"}) {
            var f=new Fixture();
            if(boundary.equals("subject"))doAnswer(a->new Subject("tenant",INVITEE,1,new byte[32],"test-key",a.getArgument(2),NOW,NOW.plusNanos(500))).when(f.subjects).resolve(any(),anyString(),any());
            else when(f.permits.forParticipant(any())).thenReturn(new ReferralBindingPermitPort.Permit(PARTICIPANT,"terms-v1","b".repeat(64),NOW,NOW.plusSeconds(100),NOW.plusSeconds(80),NOW.plusSeconds(200),30,NOW,NOW.plusNanos(500),true));
            when(f.repository.relation(anyString(),anyString(),anyString())).thenAnswer(a->{f.clock.now=NOW.plusNanos(600);return null;});
            assertThrows(ConflictException.class,()->f.service.bind(f.scope,f.request()),boundary);
            verify(f.repository,never()).save(any(),any(),anyString(),anyString(),anyString(),anyString());
        }
    }
    static class Fixture {
        final ReferralBindingRepository repository=mock(ReferralBindingRepository.class);final ReferralRepository participants=mock(ReferralRepository.class);
        final ReferralInviteRepository invites=mock(ReferralInviteRepository.class);final TokenBindingReadPort tokens=mock(TokenBindingReadPort.class);
        final TrustedReferralSubjectPort subjects=mock(TrustedReferralSubjectPort.class);final ReferralBindingPermitPort permits=mock(ReferralBindingPermitPort.class);
        final MutableClock clock=new MutableClock();final TenantScope scope=new TenantScope(new TenantId("tenant"),Set.of("org"),Set.of("shop"),"bff-machine",Set.of("referral:participate"));
        final ReferralBindingService service;
        Fixture() {
            var manager=mock(PlatformTransactionManager.class);when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
            service=new ReferralBindingService(repository,participants,invites,tokens,subjects,permits,clock,new ObjectMapper(),manager,1);
            var token=new Token("token-id","participant",Digests.sha256Hex(TOKEN),NOW.plusSeconds(100),null);
            when(tokens.locate(anyString(),anyString())).thenReturn(token);when(tokens.lock(anyString(),anyString())).thenReturn(token);
            when(invites.participant(anyString(),anyString(),anyBoolean())).thenReturn(new Owned(PARTICIPANT,INVITER,1));
            when(subjects.resolve(any(),anyString(),any())).thenAnswer(a->new Subject("tenant",INVITEE,1,new byte[32],"test-key",a.getArgument(2),NOW,NOW.plusSeconds(60)));
            when(permits.forParticipant(any())).thenReturn(permit(NOW,NOW.plusSeconds(100),NOW.plusSeconds(80),NOW.plusSeconds(200),30));
            when(participants.subjectIndexVersion("tenant")).thenReturn(1L);
            when(repository.lockCommand(anyString(),anyString(),anyString(),anyString(),any())).thenAnswer(a->new Command(a.getArgument(3),null));
            when(repository.lockInviter(anyString(),anyString(),anyString())).thenReturn(new Role("INVITER","participant",null,1));
            when(repository.lockInvitee(anyString(),anyString(),any(),any())).thenReturn(new Role("INVITEE",null,null,1));
        }
        Bind request(){return new Bind(TOKEN,"terms-v1","b".repeat(64),"trusted-fixture","bind-key-1","trace");}
        ReferralRelation original(){return new ReferralRelation("tenant","relation-id","campaign","org","shop","participant","token-id","definition",1,1,"artifact","a".repeat(64),NOW,NOW.plusSeconds(30),"terms-v1","b".repeat(64),"BOUND");}
    }
    static class MutableClock extends Clock {
        Instant now=NOW;@Override public Instant instant(){return now;}@Override public ZoneId getZone(){return ZoneOffset.UTC;}@Override public Clock withZone(ZoneId zone){return this;}
    }
}
