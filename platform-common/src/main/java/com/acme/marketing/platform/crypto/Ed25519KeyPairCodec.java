package com.acme.marketing.platform.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

public final class Ed25519KeyPairCodec {
    private static final byte[] SELF_TEST = "marketing-signing-key-self-test".getBytes(StandardCharsets.UTF_8);

    private Ed25519KeyPairCodec() {
    }

    public static KeyPair decode(String privateKeyBase64, String publicKeyBase64) {
        try {
            KeyFactory factory = KeyFactory.getInstance("Ed25519");
            PrivateKey privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(
                    Base64.getDecoder().decode(privateKeyBase64)));
            PublicKey publicKey = decodePublic(publicKeyBase64);
            if (!Ed25519.verify(publicKey, SELF_TEST, Ed25519.sign(privateKey, SELF_TEST))) {
                throw new IllegalArgumentException("Ed25519 private and public keys do not form a pair");
            }
            return new KeyPair(publicKey, privateKey);
        } catch (GeneralSecurityException | IllegalArgumentException failure) {
            throw new IllegalArgumentException("Ed25519 key pair is invalid", failure);
        }
    }

    public static PublicKey decodePublic(String publicKeyBase64) {
        try {
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(
                    Base64.getDecoder().decode(publicKeyBase64)));
        } catch (GeneralSecurityException | IllegalArgumentException failure) {
            throw new IllegalArgumentException("Ed25519 public key is invalid", failure);
        }
    }
}
