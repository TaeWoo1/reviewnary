package com.sellerops.vendor.delegate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;

/** <b>The TOTP secret is stored sealed, and opens only under the key it was sealed with</b> — contract §2 · D3. */
class TotpSecretBoxTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[TotpSecretBox.KEY_BYTES]);

    @Test
    void aSealedSecretIsNotTheSecretAndOpensBack() {
        TotpSecretBox box = new TotpSecretBox(KEY);
        byte[] secret = Totp.newSecret();
        byte[] sealed = box.seal(secret);
        assertThat(sealed).isNotEqualTo(secret).hasSizeGreaterThan(secret.length);
        assertThat(new String(sealed, java.nio.charset.StandardCharsets.ISO_8859_1))
                .doesNotContain(new String(secret, java.nio.charset.StandardCharsets.ISO_8859_1));
        assertThat(box.open(sealed)).isEqualTo(secret);
        assertThat(box.seal(secret)).as("a fresh nonce each time").isNotEqualTo(sealed);
    }

    @Test
    void anotherKeyOrAnAlteredBoxDoesNotOpen() {
        byte[] sealed = new TotpSecretBox(KEY).seal(Totp.newSecret());
        byte[] other = new byte[TotpSecretBox.KEY_BYTES];
        other[0] = 1;
        assertThatThrownBy(() -> new TotpSecretBox(Base64.getEncoder().encodeToString(other)).open(sealed))
                .isInstanceOf(IllegalStateException.class);
        sealed[sealed.length - 1] ^= 1;
        assertThatThrownBy(() -> new TotpSecretBox(KEY).open(sealed)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void thereIsNoDefaultKey() {
        for (String bad : new String[] {null, "", "not base64!", Base64.getEncoder().encodeToString(new byte[16])}) {
            assertThatThrownBy(() -> new TotpSecretBox(bad)).as(String.valueOf(bad))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
