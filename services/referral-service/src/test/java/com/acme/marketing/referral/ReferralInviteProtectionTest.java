package com.acme.marketing.referral;
import com.acme.marketing.referral.application.ReferralInviteProtectionPort.*;
import com.acme.marketing.referral.infrastructure.ReferralInviteConfiguration;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
/** 密码学替身验证AAD和密文隔离；生产保护Port保持默认拒绝。 */
class ReferralInviteProtectionTest {
    @Test void aeadRejectsTenantRequestExpiryAndCiphertextSubstitution() {
        var now=Instant.parse("2026-09-08T00:00:00Z");
        var context=new Context("tenant","a".repeat(64),"request-1","b".repeat(64),"token-id","c".repeat(64),now.plusSeconds(100),now.plusSeconds(10));
        var protection=new InviteProtectionFixture();var encrypted=protection.encrypt(context,"opaque-test-token");
        assertEquals("opaque-test-token",protection.decrypt(context,encrypted));
        for(Context changed:new Context[]{
            new Context("other",context.subjectKey(),context.idempotencyKey(),context.requestHash(),context.tokenId(),context.tokenHash(),context.tokenExpiresAt(),context.replayUntil()),
            new Context(context.tenantId(),context.subjectKey(),"request-2",context.requestHash(),context.tokenId(),context.tokenHash(),context.tokenExpiresAt(),context.replayUntil()),
            new Context(context.tenantId(),context.subjectKey(),context.idempotencyKey(),context.requestHash(),context.tokenId(),context.tokenHash(),context.tokenExpiresAt(),now.plusSeconds(11))})
            assertThrows(RuntimeException.class,()->protection.decrypt(changed,encrypted));
        byte[] corrupted=encrypted.bytes();corrupted[corrupted.length-1]^=1;
        assertThrows(RuntimeException.class,()->protection.decrypt(context,new Protected(encrypted.keyId(),corrupted)));
        assertEquals("opaque-test-token",protection.decrypt(context,encrypted));
    }
    @Test void defaultProtectionNeverFallsBackToPlaintext() {
        var protection=new ReferralInviteConfiguration().inviteProtection();
        assertThrows(RuntimeException.class,()->protection.encrypt(null,"must-not-be-stored"));
        assertThrows(RuntimeException.class,()->protection.decrypt(null,null));
    }
}
