package com.sellerops.vendor.delegate;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** <b>The codes are the ones an authenticator app shows</b> — RFC 6238 Appendix B, SHA-1, cut to six digits. */
class TotpTest {

    /** The RFC's SHA-1 seed. */
    private static final byte[] SEED = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    @Test
    void theRfcTestVectorsReproduce() {
        // RFC 6238 Appendix B gives eight digits; an app shows the last six.
        assertThat(Totp.code(SEED, Totp.step(Instant.ofEpochSecond(59)))).isEqualTo("287082");
        assertThat(Totp.code(SEED, Totp.step(Instant.ofEpochSecond(1111111109)))).isEqualTo("081804");
        assertThat(Totp.code(SEED, Totp.step(Instant.ofEpochSecond(1234567890)))).isEqualTo("005924");
        assertThat(Totp.code(SEED, Totp.step(Instant.ofEpochSecond(2000000000)))).isEqualTo("279037");
    }

    @Test
    void theSecretIsShownAsRfc4648Base32() {
        assertThat(Totp.base32(SEED)).isEqualTo("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
        assertThat(Totp.base32(new byte[] {(byte) 0xff})).isEqualTo("74");
        assertThat(Totp.newSecret()).hasSize(Totp.SECRET_BYTES);
    }

    @Test
    void oneStepEitherSideCountsAndTwoDoNot() {
        Instant at = Instant.ofEpochSecond(1_800_000_000L);
        long now = Totp.step(at);
        for (int d = -1; d <= 1; d++) {
            assertThat(Totp.matchingStep(SEED, Totp.code(SEED, now + d), at)).hasValue(now + d);
        }
        assertThat(Totp.matchingStep(SEED, Totp.code(SEED, now + 2), at)).isEmpty();
        assertThat(Totp.matchingStep(SEED, Totp.code(SEED, now - 2), at)).isEmpty();
    }

    @Test
    void onlySixDigitsAreACode() {
        Instant at = Instant.ofEpochSecond(59);
        assertThat(Totp.matchingStep(SEED, "287 082", at)).isPresent();
        for (String bad : new String[] {null, "", "28708", "2870820", "28708a", "94287082"}) {
            assertThat(Totp.matchingStep(SEED, bad, at)).as(String.valueOf(bad)).isEmpty();
        }
    }
}
