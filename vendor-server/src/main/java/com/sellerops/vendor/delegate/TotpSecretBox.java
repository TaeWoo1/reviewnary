package com.sellerops.vendor.delegate;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * <b>The one secret this server must read back</b> — a delegate's TOTP secret (contract §2 · D3).
 *
 * <p>Passwords and codes are hashed, but a TOTP secret is needed in the clear to check each code, so it is sealed
 * with AES-256-GCM under {@code VENDOR_TOTP_KEY} (base64, 32 bytes) and stored as {@code nonce ‖ ciphertext}. A copy
 * of the database alone therefore yields no second factor. Like the session key, there is no default: the server
 * refuses to start without one.
 */
@Component
public class TotpSecretBox {

    static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKey key;

    public TotpSecretBox(@Value("${vendor.totp.key:}") String base64Key) {
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key == null ? "" : base64Key.trim());
        } catch (IllegalArgumentException e) {
            raw = new byte[0];
        }
        if (raw.length != KEY_BYTES) {
            throw new IllegalStateException("VENDOR_TOTP_KEY must be set to " + KEY_BYTES + " bytes, base64-encoded");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    public byte[] seal(byte[] secret) {
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] sealed = cipher.doFinal(secret);
            return ByteBuffer.allocate(NONCE_BYTES + sealed.length).put(nonce).put(sealed).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM unavailable", e);
        }
    }

    /** Opens a sealed secret; throws if it was sealed under another key or altered. */
    public byte[] open(byte[] sealed) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, Arrays.copyOf(sealed, NONCE_BYTES)));
            return cipher.doFinal(sealed, NONCE_BYTES, sealed.length - NONCE_BYTES);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("sealed TOTP secret does not open under this key", e);
        }
    }
}
