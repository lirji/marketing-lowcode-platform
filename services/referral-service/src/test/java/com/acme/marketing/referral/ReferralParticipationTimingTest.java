package com.acme.marketing.referral;

import com.acme.marketing.platform.error.ConflictException;
import com.acme.marketing.platform.identity.*;
import com.acme.marketing.referral.application.*;
import com.acme.marketing.referral.application.ReferralParticipationPermitPort.Permit;
import com.acme.marketing.referral.application.TrustedReferralSubjectPort.Subject;
import java.time.*;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 只验证纳秒时效裁决与微秒持久化分离；不替代真实数据库事务验收。 */
class ReferralParticipationTimingTest {
    static final Instant BASE=Instant.parse("2026-09-08T00:00:00Z");

    @Test void lockWaitCannotReopenExpiredSubjectPermitOrCampaign() {
        for(String boundary:new String[]{"subject","permit","campaign"}) {
            var f=new Fixture(boundary,BASE.plusNanos(600));
            var error=assertThrows(ConflictException.class,()->f.join(),boundary);
            assertEquals(boundary.equals("subject")?"REFERRAL_IDENTITY_UNAVAILABLE":"REFERRAL_PARTICIPATION_UNAVAILABLE",error.code());
            verify(f.repository,never()).insert(any(),any());
            verify(f.repository,never()).appendJoined(any(),anyString(),anyString(),anyString(),anyString());
            verify(f.repository,never()).complete(anyString(),anyString(),anyString(),anyString(),any());
        }
    }

    @Test void exactNanosecondDeadlineIsExclusive() {
        for(String boundary:new String[]{"subject","permit","campaign"}) {
            var f=new Fixture(boundary,BASE.plusNanos(500));
            assertThrows(ConflictException.class,()->f.join(),boundary);
            verify(f.repository,never()).insert(any(),any());
        }
    }

    @Test void stillValidNanosecondEvidencePersistsMicrosecondTimestamp() {
        for(String boundary:new String[]{"subject","permit","campaign"}) {
            var f=new Fixture(boundary,BASE.plusNanos(400));
            var participant=f.join();
            assertEquals(BASE,participant.createdAt());
            verify(f.repository).insert(eq(participant),any());
            verify(f.repository).complete(eq("tenant"),eq("a".repeat(64)),eq("join-key-1"),eq(participant.participantId()),eq(BASE));
        }
    }

    /** 锁仓储推进时钟，不通过sleep制造偶发时间边界。 */
    static class Fixture {
        final ReferralRepository repository=mock(ReferralRepository.class);
        final MutableClock clock=new MutableClock();
        final ReferralParticipationService service;
        final TenantScope scope=new TenantScope(new TenantId("tenant"),Set.of("org"),Set.of("shop"),"bff-machine",Set.of("referral:participate"));
        Fixture(String boundary,Instant afterLock) {
            var subjects=mock(TrustedReferralSubjectPort.class);
            var permits=mock(ReferralParticipationPermitPort.class);
            var manager=mock(PlatformTransactionManager.class);
            when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
            when(subjects.resolve(any(),anyString(),any())).thenAnswer(a->new Subject("tenant","a".repeat(64),1,new byte[32],"test-key",a.getArgument(2),BASE,
                    boundary.equals("subject")?BASE.plusNanos(500):BASE.plusSeconds(60)));
            when(permits.current(anyString(),anyString(),anyString(),anyString())).thenReturn(new Permit("tenant","campaign","org","shop","definition",1,1,"artifact","b".repeat(64),1,
                    BASE,boundary.equals("campaign")?BASE.plusNanos(500):BASE.plusSeconds(100),BASE,
                    boundary.equals("permit")?BASE.plusNanos(500):BASE.plusSeconds(10),true));
            when(repository.subjectIndexVersion("tenant")).thenReturn(1L);
            when(repository.lockCommand(anyString(),anyString(),anyString(),anyString(),any())).thenAnswer(a->new ReferralRepository.Command(a.getArgument(3),null));
            when(repository.lockRole(anyString(),anyString(),any(),any())).thenAnswer(a->{clock.now=afterLock;return new ReferralRepository.Role("INVITER",null,1);});
            service=new ReferralParticipationService(repository,subjects,permits,clock,new ObjectMapper(),manager,1);
        }
        com.acme.marketing.referral.domain.ReferralParticipant join() {
            return service.join(scope,new ReferralParticipationService.Join("campaign","org","shop","trusted-fixture","join-key-1","trace"));
        }
    }
    static class MutableClock extends Clock {
        Instant now=BASE;
        @Override public Instant instant(){return now;}
        @Override public ZoneId getZone(){return ZoneOffset.UTC;}
        @Override public Clock withZone(ZoneId zone){return this;}
    }
}
