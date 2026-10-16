package com.sellerops.vendorlink;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Arrays;
import java.util.Base64;

/**
 * This install's X25519 key pair — contract §2 / §5. The server keeps the public half and seals Cafe24 relay results
 * to it; the private half stays on this PC, sealed under the vault key ring.
 *
 * @param publicKey  the raw 32-byte public key (the last 32 bytes of its X.509 encoding)
 * @param privateKey the PKCS#8 encoding of the private key
 */
public record InstallKeyPair(byte[] publicKey, byte[] privateKey) {

    private static final int RAW_LENGTH = 32;

    public static InstallKeyPair generate() {
        try {
            KeyPair pair = KeyPairGenerator.getInstance("X25519").generateKeyPair();
            byte[] spki = pair.getPublic().getEncoded();
            return new InstallKeyPair(Arrays.copyOfRange(spki, spki.length - RAW_LENGTH, spki.length),
                    pair.getPrivate().getEncoded());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("X25519 키를 만들 수 없습니다.", e);
        }
    }

    public String publicKeyBase64() {
        return Base64.getEncoder().encodeToString(publicKey);
    }
}
