package com.acme.marketing.benefit;

import com.acme.marketing.benefit.application.*;
import com.acme.marketing.benefit.domain.ReferralAwardPreparation.Identity;
import com.acme.marketing.benefit.infrastructure.ReferralCandidateProtectionConfiguration;
import com.acme.marketing.benefit.infrastructure.persistence.ReferralStorageTime;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/** 只用本地随机测试密钥验证保护合同，不提供生产AEAD/KMS实现。 */
class ReferralCandidateSnapshotTest {
    @Test void randomEncryptionRoundTripsExactCandidateAndRejectsCopiedBindingWithWrongIdentity() throws Exception {
        var f=new ReferralAwardIntentAssemblerTest.Fixture();var candidate=f.assemble();var id=identity(candidate);
        var service=new ReferralCandidateSnapshotService(new ProtectionFixture(),new ObjectMapper());
        var first=service.protect(id,candidate);var second=service.protect(id,candidate);
        assertFalse(Arrays.equals(first.encrypted().ciphertext(),second.encrypted().ciphertext()));
        assertEquals(new ObjectMapper().writeValueAsString(candidate.intent()),service.restore(id,first).payload());
        var other=new Identity(id.tenantId(),id.sourceSystem(),id.sourceRequestId(),id.rewardId(),"sha256:"+"c".repeat(64),id.payloadHash(),id.qualificationRevision());
        var copied=new ReferralCandidateSnapshotService.Snapshot(ReferralCandidateSnapshotService.binding(other),first.encrypted());
        assertThrows(IllegalStateException.class,()->service.restore(other,copied));
        byte[] corrupted=first.encrypted().ciphertext();corrupted[corrupted.length-1]^=1;
        var tampered=new ReferralCandidateSnapshotService.Snapshot(first.bindingDigest(),new ReferralCandidateProtectionPort.Encrypted("fixture",corrupted));
        assertThrows(IllegalStateException.class,()->service.restore(id,tampered));
    }
    @Test void plaintextHashMustMatchAndDefaultSourcesAreClosed() throws Exception {
        var f=new ReferralAwardIntentAssemblerTest.Fixture();var candidate=f.assemble();var id=identity(candidate);
        var bad=new ReferralCandidateProtectionPort(){
            public Encrypted encrypt(byte[] aad,byte[] plaintext){return new Encrypted("bad",new byte[32]);}
            public byte[] decrypt(byte[] aad,Encrypted cipher){return "private-subject-invalid".getBytes(java.nio.charset.StandardCharsets.UTF_8);}
        };
        var service=new ReferralCandidateSnapshotService(bad,new ObjectMapper());
        assertThrows(IllegalStateException.class,()->service.protect(id,candidate));
        var snapshot=new ReferralCandidateSnapshotService.Snapshot(ReferralCandidateSnapshotService.binding(id),new ReferralCandidateProtectionPort.Encrypted("bad",new byte[32]));
        var error=assertThrows(IllegalStateException.class,()->service.restore(id,snapshot));assertNull(error.getCause());assertFalse(error.toString().contains("private-subject"));
        var disabled=new ReferralCandidateSnapshotService(new ReferralCandidateProtectionConfiguration().referralCandidateProtectionPort(),new ObjectMapper());
        assertThrows(IllegalStateException.class,()->disabled.protect(id,candidate));
    }
    @Test void protectionCannotRunUnderDatabaseTransaction() throws Exception {
        var f=new ReferralAwardIntentAssemblerTest.Fixture();var candidate=f.assemble();var id=identity(candidate);var service=new ReferralCandidateSnapshotService(new ProtectionFixture(),new ObjectMapper());
        var snapshot=service.protect(id,candidate);TransactionSynchronizationManager.setActualTransactionActive(true);
        try {assertThrows(IllegalStateException.class,()->service.protect(id,candidate));assertThrows(IllegalStateException.class,()->service.restore(id,snapshot));}
        finally{TransactionSynchronizationManager.setActualTransactionActive(false);}
    }
    @Test void mapperXmlParsesWithoutStartingDatabaseOrReadingDatasource() throws Exception {
        var datasource=org.mockito.Mockito.mock(javax.sql.DataSource.class);
        var factory=new org.mybatis.spring.SqlSessionFactoryBean();factory.setDataSource(datasource);
        factory.setMapperLocations(new org.springframework.core.io.ClassPathResource("mapper/ReferralPreparationMapper.xml"));
        var configuration=factory.getObject().getConfiguration();
        assertTrue(configuration.hasStatement("com.acme.marketing.benefit.infrastructure.persistence.mapper.ReferralPreparationMapper.compareAndSet"));
        org.mockito.Mockito.verifyNoInteractions(datasource);
    }
    @Test void microsecondStorageAndRemainderPreserveExactPositiveAndNegativeInstants() {
        for(Instant value:new Instant[]{Instant.parse("2026-09-08T00:00:00.000000500Z"),Instant.parse("1969-12-31T23:59:59.999999999Z"),Instant.EPOCH})
            assertEquals(value,ReferralStorageTime.restore(ReferralStorageTime.micros(value),ReferralStorageTime.remainder(value)));
        assertNull(ReferralStorageTime.restore(null,null));
        assertThrows(IllegalStateException.class,()->ReferralStorageTime.restore(ReferralStorageTime.micros(Instant.EPOCH),1000));
        assertThrows(IllegalStateException.class,()->ReferralStorageTime.restore(null,0));
    }
    static Identity identity(ReferralAwardIntentAssembler.Candidate c){return new Identity("tenant",c.intent().sourceSystem(),c.intent().sourceRequestId(),c.intent().sourceBusinessNo(),c.stableClaimsDigest(),c.payloadHash(),1);}
    /** 纯测试AEAD实现；没有数据库/容器静态初始化。 */
    static class ProtectionFixture implements ReferralCandidateProtectionPort {
        final SecretKey key;
        ProtectionFixture() throws Exception{var generator=KeyGenerator.getInstance("AES");generator.init(256);key=generator.generateKey();}
        public Encrypted encrypt(byte[] aad,byte[] plain){try{byte[] iv=new byte[12];new SecureRandom().nextBytes(iv);Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key,new GCMParameterSpec(128,iv));c.updateAAD(aad);byte[] cipher=c.doFinal(plain);return new Encrypted("fixture",ByteBuffer.allocate(iv.length+cipher.length).put(iv).put(cipher).array());}catch(Exception e){throw new IllegalStateException(e);}}
        public byte[] decrypt(byte[] aad,Encrypted encrypted){try{byte[] data=encrypted.ciphertext();Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,Arrays.copyOf(data,12)));c.updateAAD(aad);return c.doFinal(Arrays.copyOfRange(data,12,data.length));}catch(Exception e){throw new IllegalStateException(e);}}
    }
}
