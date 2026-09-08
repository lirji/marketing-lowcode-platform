package com.acme.marketing.contracts.referral;

import static org.junit.jupiter.api.Assertions.*;
import com.acme.marketing.platform.crypto.SignedTokenCodec;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReferralAwardAuthorizationCodecTest {
    private static final Instant NOW = Instant.parse("2026-09-08T00:00:00Z");
    private static final KeyPair KEY = key();
    private static KeyPair key() {
        try { return KeyPairGenerator.getInstance("Ed25519").generateKeyPair(); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static ReferralAwardAuthorizationClaims claims(String subject, long revision, Instant iat, Instant exp) {
        return new ReferralAwardAuthorizationClaims("referral-issuer", ReferralAwardAuthorizationCodec.AUDIENCE,
                "tenant", "org", "shop", "reward", ReferralAwardIdentity.sourceRequestId("tenant","reward"), "campaign", "definition",
                1, 2, "artifact", "sha256:" + "b".repeat(64), "participant", "relation", null,
                subject, ReferralAwardAuthorizationClaims.Role.INVITEE, "rule", 1, revision, iat, exp);
    }
    private static ReferralAwardAuthorizationClaims normal() { return claims("用户😀", 1, NOW, NOW.plusSeconds(60)); }
    private static ReferralAwardAuthorizationCodec.Expected expected() {
        return new ReferralAwardAuthorizationCodec.Expected("referral-issuer", "tenant", "org", "shop", Duration.ofSeconds(60));
    }
    private static String token(ReferralAwardAuthorizationClaims c) { return ReferralAwardAuthorizationCodec.encode("key-1",KEY.getPrivate(),c); }
    private static ReferralAwardAuthorizationCodec.Verified verify(String t) {
        return ReferralAwardAuthorizationCodec.verify(t, id -> id.equals("key-1") ? KEY.getPublic() : null, expected(), NOW);
    }
    private static Map<String,String> fields() {
        return new LinkedHashMap<>(SignedTokenCodec.decodeAndVerify(token(normal()), ignored -> KEY.getPublic()).claims());
    }
    private static void rejectsFields(Map<String,String> m) {
        assertThrows(IllegalArgumentException.class, () -> verify(SignedTokenCodec.encode("key-1",KEY.getPrivate(),m)));
    }
    @Test void realSignatureRoundtripAndRedactedLogs() {
        var c = normal(); var v = verify(token(c));
        assertEquals(c,v.claims()); assertEquals("key-1",v.keyId());
        assertFalse(c.toString().contains(c.beneficiarySubject()));
        assertFalse(v.toString().contains(c.beneficiarySubject()));
    }
    @Test void wrongSignatureUnknownKeyAndMalformedTokensRejected() {
        assertThrows(IllegalArgumentException.class, () -> verify(SignedTokenCodec.encode("key-1",key().getPrivate(),fields())));
        assertThrows(IllegalArgumentException.class, () -> verify(SignedTokenCodec.encode("unknown",KEY.getPrivate(),fields())));
        for (String t : new String[]{"", "a.b.c.d", "a..b", "a.b.c=", "a".repeat(16385)})
            assertThrows(IllegalArgumentException.class, () -> verify(t));
        var failure=assertThrows(IllegalArgumentException.class, () -> ReferralAwardAuthorizationCodec.verify(token(normal()),
                ignored -> { throw new IllegalStateException("private-key-server"); }, expected(), NOW));
        assertNull(failure.getCause()); assertFalse(failure.getMessage().contains("private-key-server"));
    }
    @Test void everyTrustedScopeBoundaryIsChecked() {
        for (String field : new String[]{"issuer", "audience", "tenantId", "organizationId", "shopId", "format"}) {
            var m=fields(); m.put(field,"other"); rejectsFields(m);
        }
    }
    @Test void timeWindowAndExplicitLifetimeFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> verify(token(claims("s",1,NOW.plusSeconds(1),NOW.plusSeconds(60)))));
        assertThrows(IllegalArgumentException.class, () -> verify(token(claims("s",1,NOW.minusSeconds(60),NOW))));
        assertThrows(IllegalArgumentException.class, () -> verify(token(claims("s",1,NOW,NOW.plusSeconds(61)))));
        var c=normal();
        assertDoesNotThrow(() -> ReferralAwardAuthorizationCodec.verifyTime(c,Duration.ofSeconds(60),NOW.plusSeconds(59)));
        assertThrows(IllegalArgumentException.class, () -> ReferralAwardAuthorizationCodec.verifyTime(c,Duration.ofSeconds(60),NOW.plusSeconds(60)));
        assertThrows(IllegalArgumentException.class, () -> new ReferralAwardAuthorizationCodec.Expected("i","t","o","s",Duration.ZERO));
    }
    @Test void exactCanonicalFieldsOnly() {
        var unknown=fields();unknown.put("cashAmount","100");rejectsFields(unknown);
        for (String field : fields().keySet()) { var missing=fields();missing.remove(field);rejectsFields(missing); }
        for (String value : new String[]{"01","+1","1.0","9223372036854775808"}) {
            var noncanonical=fields();noncanonical.put("definitionVersion",value);rejectsFields(noncanonical);
        }
        var alternateTime=fields();alternateTime.put("issuedAt","2026-09-08T00:00:00.000Z");rejectsFields(alternateTime);
    }
    @Test void invalidIdentityQuantityRoleAndRelationShapeRejected() {
        for (String field : new String[]{"quantity","qualificationRevision","definitionVersion","generation"}) {
            var m=fields();m.put(field,"0");rejectsFields(m);
        }
        var m=fields();m.put("quantity","2");rejectsFields(m);
        m=fields();m.put("milestone","3");rejectsFields(m);
        m=fields();m.put("relationId","");rejectsFields(m);
        m=fields();m.put("relationId","");m.put("milestone","3");rejectsFields(m);
        m.put("role","INVITER"); assertEquals(3L, verify(SignedTokenCodec.encode("key-1",KEY.getPrivate(),m)).claims().milestone());
        m=fields();m.put("sourceRequestId","old-drools-id");rejectsFields(m);
    }
    @Test void resSigningAndRotationKeepStableBusinessDigest() {
        var original=normal();var renewed=claims(original.beneficiarySubject(),1,NOW.plusSeconds(1),NOW.plusSeconds(61));
        var otherKey=key();
        var renewedToken=ReferralAwardAuthorizationCodec.encode("key-2",otherKey.getPrivate(),renewed);
        var verified=ReferralAwardAuthorizationCodec.verify(renewedToken,ignored -> otherKey.getPublic(),expected(),NOW.plusSeconds(1));
        assertEquals(verify(token(original)).stableDigest(),verified.stableDigest());
        assertNotEquals(ReferralAwardAuthorizationCodec.stableDigest(original),ReferralAwardAuthorizationCodec.stableDigest(claims("other",1,NOW,NOW.plusSeconds(60))));
        assertNotEquals(ReferralAwardAuthorizationCodec.stableDigest(original),ReferralAwardAuthorizationCodec.stableDigest(claims(original.beneficiarySubject(),2,NOW,NOW.plusSeconds(60))));
    }
    @Test void allBusinessFieldsAreInStableDigest() {
        var original=normal();var stable=ReferralAwardAuthorizationCodec.stableDigest(original);
        for (String field : new String[]{"rewardId","campaignId","definitionId","participantId","relationId","ruleId","beneficiarySubject"}) {
            var m=fields();m.put(field,m.get(field)+"-changed");
            if (field.equals("rewardId")) m.put("sourceRequestId",ReferralAwardIdentity.sourceRequestId(m.get("tenantId"),m.get("rewardId")));
            assertNotEquals(stable,verify(SignedTokenCodec.encode("key-1",KEY.getPrivate(),m)).stableDigest(),field);
        }
        for (String field : new String[]{"definitionVersion","generation","qualificationRevision"}) {
            var m=fields();m.put(field,"3"); assertNotEquals(stable,verify(SignedTokenCodec.encode("key-1",KEY.getPrivate(),m)).stableDigest(),field);
        }
    }
    @Test void unicodeIsExactAndUnpairedSurrogatesRejected() {
        var left=claims("é",1,NOW,NOW.plusSeconds(60)); var right=claims("e\u0301",1,NOW,NOW.plusSeconds(60));
        assertNotEquals(ReferralAwardAuthorizationCodec.stableDigest(left),ReferralAwardAuthorizationCodec.stableDigest(right));
        assertEquals("😀".repeat(256),verify(token(claims("😀".repeat(256),1,NOW,NOW.plusSeconds(60)))).claims().beneficiarySubject());
        for (String subject : new String[]{"\uD800", "\uDC00", "😀".repeat(257)})
            assertThrows(IllegalArgumentException.class, () -> claims(subject,1,NOW,NOW.plusSeconds(60)));
    }
    @Test void sourceIdentityIsPermanentScopedAndSelfConsistent() {
        String identity=ReferralAwardIdentity.sourceRequestId("tenant","reward");
        assertEquals(identity,normal().sourceRequestId());
        assertNotEquals(identity,ReferralAwardIdentity.sourceRequestId("other-tenant","reward"));
        assertNotEquals(identity,ReferralAwardIdentity.sourceRequestId("tenant","other-reward"));
        assertNotEquals(ReferralAwardIdentity.sourceRequestId("ab","c"),ReferralAwardIdentity.sourceRequestId("a","bc"));
        assertNotEquals(ReferralAwardIdentity.sourceRequestId("é","reward"),ReferralAwardIdentity.sourceRequestId("e\u0301","reward"));
        var m=fields();m.put("rewardId","other");rejectsFields(m);
        m=fields();m.put("tenantId","other");rejectsFields(m);
        assertEquals(identity,claims("another-beneficiary",3,NOW.plusSeconds(1),NOW.plusSeconds(61)).sourceRequestId());
    }

}
