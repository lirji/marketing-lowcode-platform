package com.acme.marketing.platform.crypto;

import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

public final class SignedTokenCodec {
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private SignedTokenCodec() {
    }

    public static String encode(String keyId, PrivateKey privateKey, Map<String, String> claims) {
        requireKeyId(keyId);
        byte[] payload = CanonicalMapCodec.encode(claims);
        String header = ENCODER.encodeToString(keyId.getBytes(StandardCharsets.UTF_8));
        String body = ENCODER.encodeToString(payload);
        byte[] signingInput = (header + "." + body).getBytes(StandardCharsets.US_ASCII);
        return header + "." + body + "." + ENCODER.encodeToString(Ed25519.sign(privateKey, signingInput));
    }

    public static VerifiedToken decodeAndVerify(String token, Function<String, PublicKey> keyResolver) {
        Objects.requireNonNull(token, "token");
        String[] pieces = token.split("\\.", -1);
        if (pieces.length != 3) {
            throw new IllegalArgumentException("signed token must have three segments");
        }
        try {
            String keyId = new String(DECODER.decode(pieces[0]), StandardCharsets.UTF_8);
            requireKeyId(keyId);
            PublicKey key = Objects.requireNonNull(keyResolver.apply(keyId), "unknown signing key");
            byte[] signingInput = (pieces[0] + "." + pieces[1]).getBytes(StandardCharsets.US_ASCII);
            if (!Ed25519.verify(key, signingInput, DECODER.decode(pieces[2]))) {
                throw new IllegalArgumentException("invalid token signature");
            }
            return new VerifiedToken(keyId, CanonicalMapCodec.decode(DECODER.decode(pieces[1])));
        } catch (IllegalArgumentException invalid) {
            throw invalid;
        } catch (RuntimeException malformed) {
            throw new IllegalArgumentException("malformed signed token", malformed);
        }
    }

    private static void requireKeyId(String keyId) {
        if (keyId == null || !keyId.matches("[a-zA-Z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("invalid key id");
        }
    }

    public record VerifiedToken(String keyId, Map<String, String> claims) {
        public VerifiedToken {
            claims = Map.copyOf(claims);
        }
    }
}
