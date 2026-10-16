package com.sellerops.vendor.install;

import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;

/**
 * An install's X25519 public key as the PC sends it: 32 raw bytes, base64 (standard or url-safe). Contract §2 / §5 —
 * Cafe24 relay results are sealed to it, so the server only ever needs the public half.
 *
 * <p>No proof of possession is asked for: X25519 is a key-agreement key and cannot sign, and a PC that registers a
 * key it does not hold only makes its own sealed results unreadable to itself. The all-zero key is refused (it is the
 * one value a careless client produces by default, and it makes every shared secret zero).
 */
public final class InstallPublicKey {

    public static final int LENGTH = 32;

    private InstallPublicKey() {
    }

    public static Optional<byte[]> parse(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return Optional.empty();
        }
        byte[] raw;
        try {
            String s = encoded.trim();
            raw = s.contains("-") || s.contains("_") ? Base64.getUrlDecoder().decode(s) : Base64.getDecoder().decode(s);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (raw.length != LENGTH || Arrays.equals(raw, new byte[LENGTH])) {
            return Optional.empty();
        }
        return Optional.of(raw);
    }
}
