package com.sellerops.vendor;

import com.sellerops.vendor.delegate.Totp;
import java.io.ByteArrayOutputStream;
import java.time.Instant;

/** What a delegate's authenticator app does, for tests: read the base32 secret and show a step's code. */
public final class TestTotp {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private TestTotp() {
    }

    public static byte[] decode(String base32) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (char c : base32.toCharArray()) {
            buffer = (buffer << 5) | ALPHABET.indexOf(c);
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }

    /** The code {@code stepsAhead} steps after the one containing {@code at}. */
    public static String code(String base32, Instant at, int stepsAhead) {
        return Totp.code(decode(base32), Totp.step(at) + stepsAhead);
    }
}
