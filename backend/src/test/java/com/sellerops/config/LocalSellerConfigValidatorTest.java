package com.sellerops.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sellerops.outbound.OutboundGate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * <b>A seller-PC install refuses to boot in a shape that reaches past the PC</b> — scope lock v1.14 ① · ⑤.
 *
 * <p>Both directions are load-bearing: with the flag off every existing deployment must boot exactly as
 * before (even with every value that local mode refuses), and with it on a single reach-past setting stops
 * the process. Each condition is proven by a clean install that passes and the same install with that one
 * setting broken, which fails — "passes" alone would prove nothing.
 */
class LocalSellerConfigValidatorTest {

    private static final String SECRET = "a-per-install-generated-secret-of-at-least-32-chars";
    private static final String H2 = "jdbc:h2:mem:sellerops_test";

    /** A passing local configuration arms the process-wide outbound gate; leave it as every other test expects. */
    @AfterEach
    void disarm() {
        OutboundGate.arm(false);
    }

    private static LocalSellerConfigValidator clean() {
        return v(true, "127.0.0.1", "jdbc:postgresql://localhost:5432/sellerops", SECRET,
                "http://localhost:5173", "", "off", "", "");
    }

    private static LocalSellerConfigValidator v(boolean enabled, String address, String db, String jwt,
                                                String cors, String sentry, String mail,
                                                String google, String naver) {
        return new LocalSellerConfigValidator(enabled, address, db, jwt, cors, sentry, mail, google, naver);
    }

    @Test
    void offChecksNothingEvenForEveryValueLocalModeRefuses() {
        LocalSellerConfigValidator off = v(false, "", "jdbc:postgresql://db.example.com:5432/x",
                LocalSellerConfigValidator.SHIPPED_JWT_SECRET, "https://app.example.com",
                "https://key@sentry.example.com/1", "smtp", "gid", "nid");
        assertThat(off.problems()).isEmpty();
        off.afterPropertiesSet();
        assertThat(OutboundGate.armed()).as("an ordinary deployment never arms the gate").isFalse();
    }

    @Test
    void aCleanLocalInstallBoots() {
        assertThat(clean().problems()).isEmpty();
        clean().afterPropertiesSet();
        assertThat(OutboundGate.armed()).as("a passing local install arms the outbound gate").isTrue();
        assertThat(v(true, "::1", H2, SECRET, "http://127.0.0.1:5173", "", "dev-outbox", "", "").problems())
                .isEmpty();
    }

    @Test
    void bindAddressMustBeLoopback() {
        for (String bad : List.of("", "0.0.0.0", "192.168.0.10", "seller-pc.local")) {
            assertThat(v(true, bad, H2, SECRET, "http://localhost:5173", "", "off", "", "").problems())
                    .as(bad).singleElement().asString().startsWith("SERVER_ADDRESS");
        }
    }

    @Test
    void databaseMustBeOnThisPc() {
        assertThat(v(true, "127.0.0.1", "jdbc:postgresql://db.example.com:5432/sellerops", SECRET,
                "http://localhost:5173", "", "off", "", "").problems())
                .singleElement().asString().startsWith("SPRING_DATASOURCE_URL");
    }

    @Test
    void theShippedOrAShortJwtSecretIsRefused() {
        for (String bad : List.of("", LocalSellerConfigValidator.SHIPPED_JWT_SECRET, "short-secret")) {
            assertThat(v(true, "127.0.0.1", H2, bad, "http://localhost:5173", "", "off", "", "").problems())
                    .as(bad).singleElement().asString().startsWith("SELLEROPS_JWT_SECRET");
        }
    }

    @Test
    void corsOriginMustBeLoopback() {
        for (String bad : List.of("", "https://app.example.com", "null", "file:///tmp/x.html")) {
            assertThat(v(true, "127.0.0.1", H2, SECRET, bad, "", "off", "", "").problems())
                    .as(bad).singleElement().asString().startsWith("SELLEROPS_CORS_ORIGIN");
        }
    }

    @Test
    void sentrySmtpAndSocialLoginAreDestinationsOffThePc() {
        assertThat(v(true, "127.0.0.1", H2, SECRET, "http://localhost:5173",
                "https://key@sentry.example.com/1", "off", "", "").problems())
                .singleElement().asString().startsWith("SENTRY_DSN");
        assertThat(v(true, "127.0.0.1", H2, SECRET, "http://localhost:5173", "", "SMTP", "", "").problems())
                .singleElement().asString().startsWith("SELLEROPS_MAIL_MODE");
        assertThat(v(true, "127.0.0.1", H2, SECRET, "http://localhost:5173", "", "off", "gid", "").problems())
                .singleElement().asString().startsWith("SELLEROPS_OAUTH_GOOGLE_CLIENT_ID");
        assertThat(v(true, "127.0.0.1", H2, SECRET, "http://localhost:5173", "", "off", "", "nid").problems())
                .singleElement().asString().startsWith("SELLEROPS_OAUTH_GOOGLE_CLIENT_ID");
    }

    @Test
    void refusalNamesTheSettingAndNeverTheValue() {
        String dsn = "https://very-secret-key@sentry.example.com/1";
        LocalSellerConfigValidator broken = v(true, "0.0.0.0", H2, "short-secret-value",
                "http://localhost:5173", dsn, "off", "", "");
        assertThatThrownBy(broken::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SERVER_ADDRESS")
                .hasMessageContaining("SENTRY_DSN")
                .hasMessageNotContaining("very-secret-key")
                .hasMessageNotContaining("short-secret-value")
                .hasMessageNotContaining("0.0.0.0");
    }
}
