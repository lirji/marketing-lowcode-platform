package com.acme.marketing.contracts.offer;

import com.acme.marketing.platform.crypto.CanonicalMapCodec;
import com.acme.marketing.platform.crypto.SignedTokenCodec;
import com.acme.marketing.platform.identity.TenantId;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public final class OfferTokenCodec {
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private OfferTokenCodec() {
    }

    public static String encode(String keyId, PrivateKey privateKey, OfferTokenClaims claims) {
        return SignedTokenCodec.encode(keyId, privateKey, toMap(claims));
    }

    public static OfferTokenClaims verify(
            String token,
            Function<String, PublicKey> keyResolver,
            TenantId expectedTenant,
            String expectedCartDigest,
            Clock clock) {
        try {
            Map<String, String> map = SignedTokenCodec.decodeAndVerify(token, keyResolver).claims();
            OfferTokenClaims claims = fromMap(map);
            if (!claims.tenantId().equals(expectedTenant)) {
                throw new OfferTokenException("OFFER_TOKEN_TENANT_MISMATCH", "offer token belongs to another tenant");
            }
            if (!claims.cartDigest().equals(expectedCartDigest)) {
                throw new OfferTokenException("OFFER_TOKEN_CART_MISMATCH", "offer token does not match trusted cart digest");
            }
            if (!claims.expiresAt().isAfter(clock.instant())) {
                throw new OfferTokenException("OFFER_TOKEN_EXPIRED", "offer token has expired");
            }
            return claims;
        } catch (OfferTokenException known) {
            throw known;
        } catch (RuntimeException invalid) {
            throw new OfferTokenException("OFFER_TOKEN_INVALID", "offer token is malformed or has an invalid signature");
        }
    }

    private static Map<String, String> toMap(OfferTokenClaims claims) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("issuer", claims.issuer());
        map.put("tenant", claims.tenantId().value());
        map.put("organization", claims.organizationId());
        map.put("subject", claims.subjectToken());
        map.put("order", claims.orderId());
        map.put("shops", encodeStrings(claims.shopIds()));
        map.put("cartDigest", claims.cartDigest());
        map.put("quote", claims.quoteId());
        map.put("request", claims.decisionRequestId());
        map.put("generation", Long.toString(claims.generation()));
        map.put("artifacts", encodeStrings(claims.artifactIds()));
        map.put("offers", encodeOffers(claims.offerLines()));
        map.put("terms", claims.termsVersion());
        map.put("issuedAt", claims.issuedAt().toString());
        map.put("expiresAt", claims.expiresAt().toString());
        map.put("nonce", claims.nonce());
        map.put("audiences", encodeStrings(claims.audienceVersions()));
        return map;
    }

    private static OfferTokenClaims fromMap(Map<String, String> map) {
        return new OfferTokenClaims(
                required(map, "issuer"),
                new TenantId(required(map, "tenant")),
                required(map, "organization"),
                required(map, "subject"),
                required(map, "order"),
                decodeStrings(required(map, "shops")),
                required(map, "cartDigest"),
                required(map, "quote"),
                required(map, "request"),
                Long.parseLong(required(map, "generation")),
                decodeStrings(required(map, "artifacts")),
                decodeOffers(required(map, "offers")),
                required(map, "terms"),
                Instant.parse(required(map, "issuedAt")),
                Instant.parse(required(map, "expiresAt")),
                required(map, "nonce"),
                decodeStrings(required(map, "audiences")));
    }

    private static String encodeOffers(List<OfferLineClaim> lines) {
        return lines.stream().map(line -> {
            Map<String, String> values = new LinkedHashMap<>();
            values.put("offerId", line.offerId());
            values.put("benefitVersion", line.benefitDefinitionVersion());
            values.put("currency", line.currency());
            values.put("minorUnits", Long.toString(line.minorUnits()));
            values.put("quantity", Integer.toString(line.quantity()));
            values.put("funding", encodeFunding(line.fundingShares()));
            return ENCODER.encodeToString(CanonicalMapCodec.encode(values));
        }).reduce((left, right) -> left + "." + right).orElse("");
    }

    private static List<OfferLineClaim> decodeOffers(String encoded) {
        if (encoded.isEmpty()) {
            return List.of();
        }
        List<OfferLineClaim> lines = new ArrayList<>();
        for (String item : encoded.split("\\.")) {
            Map<String, String> values = CanonicalMapCodec.decode(DECODER.decode(item));
            lines.add(new OfferLineClaim(
                    required(values, "offerId"), required(values, "benefitVersion"),
                    required(values, "currency"), Long.parseLong(required(values, "minorUnits")),
                    Integer.parseInt(required(values, "quantity")), decodeFunding(required(values, "funding"))));
        }
        return List.copyOf(lines);
    }

    private static String encodeFunding(List<FundingShareClaim> shares) {
        return shares.stream().map(share -> String.join("~", share.funderType(), share.funderId(),
                share.currency(), Long.toString(share.minorUnits()))).reduce((a, b) -> a + "," + b).orElse("");
    }

    private static List<FundingShareClaim> decodeFunding(String encoded) {
        if (encoded.isEmpty()) {
            return List.of();
        }
        List<FundingShareClaim> shares = new ArrayList<>();
        for (String item : encoded.split(",")) {
            String[] values = item.split("~", -1);
            if (values.length != 4) {
                throw new IllegalArgumentException("invalid funding claim");
            }
            shares.add(new FundingShareClaim(values[0], values[1], values[2], Long.parseLong(values[3])));
        }
        return List.copyOf(shares);
    }

    private static String encodeStrings(List<String> values) {
        return values.stream()
                .map(value -> ENCODER.encodeToString(value.getBytes(StandardCharsets.UTF_8)))
                .reduce((left, right) -> left + "." + right).orElse("");
    }

    private static List<String> decodeStrings(String encoded) {
        if (encoded.isEmpty()) {
            return List.of();
        }
        return List.of(encoded.split("\\.")).stream()
                .map(value -> new String(DECODER.decode(value), StandardCharsets.UTF_8)).toList();
    }

    private static String required(Map<String, String> map, String key) {
        String value = map.get(key);
        if (value == null) {
            throw new IllegalArgumentException("missing claim: " + key);
        }
        return value;
    }
}
