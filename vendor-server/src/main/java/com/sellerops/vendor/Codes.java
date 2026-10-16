package com.sellerops.vendor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Random one-time secrets and the digest they are stored as. Every bearer secret this server hands out (invite codes,
 * device codes, install tokens) is 32 random bytes, and only its SHA-256 is written anywhere — a database read is
 * never a credential.
 */
public final class Codes {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Codes() {
    }

    /** 32 random bytes, base64url without padding (43 characters). */
    public static String newCode() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Lowercase hex SHA-256 of the UTF-8 text — 64 characters. */
    public static String hash(String code) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Uniform choice from an alphabet, for short human-carried codes. */
    public static String fromAlphabet(char[] alphabet, int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(alphabet[RANDOM.nextInt(alphabet.length)]);
        }
        return sb.toString();
    }
}
