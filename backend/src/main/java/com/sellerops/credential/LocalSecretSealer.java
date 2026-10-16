package com.sellerops.credential;

import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Seals a small secret that is not a channel credential under the same vault key ring — the seller PC's install
 * token and install private key for the vendor server ({@code vendorlink}). Same envelope as
 * {@link CredentialVault} ({@link EnvelopeCipher}), same keys, same fail-closed rule: with no key configured
 * nothing is sealed and nothing is opened.
 */
@Component
public class LocalSecretSealer {

    /** A sealed value and the id of the key that sealed it. */
    public record Sealed(String keyId, byte[] envelope) {}

    private final VaultKeyRing keyRing;

    public LocalSecretSealer(VaultKeyRing keyRing) {
        this.keyRing = keyRing;
    }

    public boolean available() {
        return keyRing.activeKey().isPresent();
    }

    public Sealed seal(byte[] plaintext) {
        byte[] key = keyRing.activeKey().orElseThrow(LocalSecretSealer::noKey);
        return new Sealed(keyRing.activeKeyId(), EnvelopeCipher.seal(key, plaintext));
    }

    /** Empty when the key that sealed it is not in the ring (the caller treats that as "not linked"). */
    public Optional<byte[]> open(String keyId, byte[] envelope) {
        return keyRing.keyFor(keyId).map(key -> EnvelopeCipher.open(key, envelope));
    }

    private static IllegalStateException noKey() {
        return new IllegalStateException("암호화 키(sellerops.vault.master-key-base64)가 없어 비밀 값을 저장할 수 없습니다.");
    }
}
