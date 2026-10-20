package com.sellerops.vendor.delegate;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.OptionalLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * <b>Time-based one-time codes, RFC 6238</b> — the second factor contract §2 · D3 requires of every delegate.
 *
 * <p>The parameters every authenticator app assumes without being told: HMAC-SHA1, 30-second steps, 6 digits, a
 * 20-byte secret shown as RFC 4648 base32. A code is accepted one step either side of now (clock drift); which step
 * matched is returned so the caller can refuse the same step twice — a code seen over a shoulder is spent.
 */
public final class Totp {

    public static final int SECRET_BYTES = 20;
    public static final int DIGITS = 6;
    public static final long STEP_SECONDS = 30;
    /** Steps either side of now that still count. */
    static final int WINDOW = 1;
    private static final char[] BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private Totp() {
    }

    public static byte[] newSecret() {
        byte[] secret = new byte[SECRET_BYTES];
        RANDOM.nextBytes(secret);
        return secret;
    }

    public static long step(Instant at) {
        return Math.floorDiv(at.getEpochSecond(), STEP_SECONDS);
    }

    /** The code for one step, zero-padded to {@link #DIGITS}. */
    public static String code(byte[] secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
            String digits = Integer.toString(binary % 1_000_000);
            return "0".repeat(DIGITS - digits.length()) + digits;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA1 unavailable", e);
        }
    }

    /** The step {@code given} matches within the window around {@code at}; empty when it matches none. */
    public static OptionalLong matchingStep(byte[] secret, String given, Instant at) {
        String typed = given == null ? "" : given.replace(" ", "").trim();
        if (typed.length() != DIGITS || !typed.chars().allMatch(Character::isDigit)) {
            return OptionalLong.empty();
        }
        long now = step(at);
        for (long s = now - WINDOW; s <= now + WINDOW; s++) {
            if (MessageDigest.isEqual(code(secret, s).getBytes(), typed.getBytes())) {
                return OptionalLong.of(s);
            }
        }
        return OptionalLong.empty();
    }

    /** RFC 4648 base32 without padding — what an authenticator app is given to type or scan. */
    public static String base32(byte[] bytes) {
        StringBuilder out = new StringBuilder((bytes.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(BASE32[(buffer >> (bits - 5)) & 0x1f]);
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(BASE32[(buffer << (5 - bits)) & 0x1f]);
        }
        return out.toString();
    }
}
