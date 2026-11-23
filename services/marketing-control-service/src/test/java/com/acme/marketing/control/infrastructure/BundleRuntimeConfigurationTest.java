package com.acme.marketing.control.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import com.acme.marketing.control.application.ReleaseBundleError;
import com.acme.marketing.platform.crypto.Ed25519;
import com.acme.marketing.platform.crypto.SigningKeyRing;
import com.acme.marketing.platform.error.DomainException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** 配置按成员类型严格校验，机器令牌只允许路由到其真实租户。 */
class BundleRuntimeConfigurationTest {
    private static final String TARGETS = """
            [{"environment":"local","cell":"cell-a","runtime":"decision","namespace":"main","urls":["http://127.0.0.1:18802"],"admission":true},
             {"environment":"local","cell":"cell-a","runtime":"journey","namespace":"journey-ns","urls":["http://127.0.0.1:18803"],
              "jobId":"0123456789abcdef0123456789abcdef","parallelism":2,"participationKeyId":"participation-1"}]
            """;
    private final JsonMapper mapper = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).build();

    @Test void optionalRuntimeSpecificFieldsCanBeOmittedUnderStrictMapper() {
        assertDoesNotThrow(() -> runtime(TARGETS));
        assertEquals(ReleaseBundleError.RUNTIME_CONFIG.code(), assertThrows(DomainException.class,
                () -> runtime(TARGETS.replace("\"parallelism\":2,", ""))).code());
        assertEquals(ReleaseBundleError.RUNTIME_CONFIG.code(), assertThrows(DomainException.class,
                () -> runtime(TARGETS.replace("participation-1", ""))).code());
    }
    @Test void credentialsMatchTenantAndCannotFallBackToDevHeaders() throws Exception {
        String alice = token("tenant-a"), bob = token("tenant-b");
        var single = new BundleMachineCredential(mapper, alice);
        assertEquals("Bearer " + alice, single.authorization("tenant-a", false));
        assertThrows(DomainException.class, () -> single.authorization("tenant-b", true));
        var multi = new BundleMachineCredential(mapper, mapper.writeValueAsString(Map.of("tenant-a", alice, "tenant-b", bob)));
        assertEquals("Bearer " + bob, multi.authorization("tenant-b", false));
        assertThrows(DomainException.class, () -> new BundleMachineCredential(mapper,
                mapper.writeValueAsString(Map.of("tenant-a", bob))));
    }
    private HttpBundleReleaseRuntime runtime(String targets) {
        return new HttpBundleReleaseRuntime(mapper, Clock.systemUTC(), new SigningKeyRing("test", Ed25519.generateKeyPair()),
                targets, "", "DEV");
    }
    private static String token(String tenant) throws Exception {
        var key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), new JWTClaimsSet.Builder().claim("tenant_id", tenant).build());
        jwt.sign(new MACSigner(key));
        return jwt.serialize();
    }
}
