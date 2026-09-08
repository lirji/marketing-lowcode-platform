package com.acme.marketing.referral;
import com.acme.marketing.referral.application.ReferralInviteProtectionPort;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.atomic.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.assertFalse;
/** 仅测试源码中的真实AES-GCM替身；无生产装配路径，不等于真实KMS签收。 */
final class InviteProtectionFixture implements ReferralInviteProtectionPort {
    final byte[] key=new byte[32];final SecureRandom random=new SecureRandom();
    final AtomicInteger encrypts=new AtomicInteger(),decrypts=new AtomicInteger();
    final AtomicBoolean failNextDecrypt=new AtomicBoolean();
    volatile Runnable encryptHook=()->{},decryptHook=()->{};
    InviteProtectionFixture() { random.nextBytes(key); }
    @Override public Protected encrypt(Context context,String token) {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());encrypts.incrementAndGet();
        try {
            byte[] iv=new byte[12];random.nextBytes(iv);var cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));cipher.updateAAD(aad(context));
            byte[] body=cipher.doFinal(token.getBytes(StandardCharsets.US_ASCII));byte[] result=new byte[iv.length+body.length];
            System.arraycopy(iv,0,result,0,iv.length);System.arraycopy(body,0,result,iv.length,body.length);encryptHook.run();return new Protected("test-token-aead-key",result);
        } catch(Exception failure) { throw new IllegalStateException("fixture encryption failed"); }
    }
    @Override public String decrypt(Context context,Protected encrypted) {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());decrypts.incrementAndGet();
        if(failNextDecrypt.compareAndSet(true,false)) throw new IllegalStateException("fixture decrypt transient failure");
        try {
            if(!"test-token-aead-key".equals(encrypted.keyId())) throw new IllegalStateException();
            byte[] input=encrypted.bytes();var cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,Arrays.copyOf(input,12)));cipher.updateAAD(aad(context));
            String result=new String(cipher.doFinal(Arrays.copyOfRange(input,12,input.length)),StandardCharsets.US_ASCII);decryptHook.run();return result;
        } catch(Exception failure) { throw new IllegalStateException("fixture decryption failed"); }
    }
    private static byte[] aad(Context context) {
        var values=List.of(context.tenantId(),context.subjectKey(),context.idempotencyKey(),context.requestHash(),context.tokenId(),context.tokenHash(),context.tokenExpiresAt().toString(),context.replayUntil().toString());
        var result=new StringBuilder();for(String value:values) result.append(value.length()).append(':').append(value);
        return result.toString().getBytes(StandardCharsets.UTF_8);
    }
}
