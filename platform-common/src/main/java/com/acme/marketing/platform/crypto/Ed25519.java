package com.acme.marketing.platform.crypto;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;

public final class Ed25519 {
    private Ed25519() {
    }

    public static KeyPair generateKeyPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("Ed25519 is required by Java 21", impossible);
        }
    }

    public static byte[] sign(PrivateKey privateKey, byte[] payload) {
        try {
            Signature signature = Signature.getInstance("Ed25519");
            signature.initSign(privateKey);
            signature.update(payload);
            return signature.sign();
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("could not sign payload", failure);
        }
    }

    public static boolean verify(PublicKey publicKey, byte[] payload, byte[] signed) {
        try {
            Signature signature = Signature.getInstance("Ed25519");
            signature.initVerify(publicKey);
            signature.update(payload);
            return signature.verify(signed);
        } catch (GeneralSecurityException failure) {
            return false;
        }
    }
}
