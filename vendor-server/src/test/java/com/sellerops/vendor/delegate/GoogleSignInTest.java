package com.sellerops.vendor.delegate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * <b>Only an ID token Google issued to this client, for this trip, with a verified address, names an account</b> —
 * contract §2 (D3 revised 2026-10-26). Each check is shown passing once and failing on its own.
 */
class GoogleSignInTest {

    static final String CLIENT = "client-123.apps.googleusercontent.com";
    static final Instant NOW = Instant.parse("2026-10-26T00:00:00Z");

    static String token(Map<String, Object> claims) {
        try {
            Base64.Encoder b = Base64.getUrlEncoder().withoutPadding();
            return b.encodeToString("{\"alg\":\"RS256\"}".getBytes()) + "."
                    + b.encodeToString(new ObjectMapper().writeValueAsBytes(claims)) + ".c2ln";
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static Map<String, Object> good(String nonce) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("iss", "https://accounts.google.com");
        c.put("aud", CLIENT);
        c.put("azp", CLIENT);
        c.put("sub", "1100000000001");
        c.put("email", "Owner@Example.com");
        c.put("email_verified", true);
        c.put("nonce", nonce);
        c.put("exp", NOW.plusSeconds(300).getEpochSecond());
        return c;
    }

    @Test
    void aGoodTokenNamesTheAccountAndEachBrokenClaimIsRefused() {
        assertThat(GoogleSignIn.verify(token(good("n1")), CLIENT, "n1", NOW))
                .isEqualTo(new GoogleSignIn.Identity("1100000000001", "Owner@Example.com"));
        Map<String, Object> listed = good("n1");
        listed.put("aud", List.of("other", CLIENT));
        listed.remove("azp");
        assertThat(GoogleSignIn.verify(token(listed), CLIENT, "n1", NOW).sub()).isEqualTo("1100000000001");

        for (Map.Entry<String, Object> broken : List.of(
                Map.entry("iss", (Object) "https://evil.example"),
                Map.entry("aud", (Object) "someone-else"),
                Map.entry("azp", (Object) "someone-else"),
                Map.entry("nonce", (Object) "n2"),
                Map.entry("email_verified", (Object) false),
                Map.entry("exp", (Object) NOW.minusSeconds(120).getEpochSecond()),
                Map.entry("sub", (Object) ""))) {
            Map<String, Object> c = good("n1");
            c.put(broken.getKey(), broken.getValue());
            assertThatThrownBy(() -> GoogleSignIn.verify(token(c), CLIENT, "n1", NOW))
                    .as(broken.getKey()).isInstanceOf(GoogleSignIn.Refused.class);
        }
        assertThatThrownBy(() -> GoogleSignIn.verify("not-a-jwt", CLIENT, "n1", NOW))
                .isInstanceOf(GoogleSignIn.Refused.class);
        assertThatThrownBy(() -> GoogleSignIn.verify(token(good("n1")), CLIENT, null, NOW))
                .isInstanceOf(GoogleSignIn.Refused.class);
    }

    @Test
    void theAuthorizeAddressCarriesStateNonceAndAnS256ChallengeNeverTheVerifier() {
        GoogleSignIn google = new GoogleSignIn(CLIENT, "secret", "http://127.0.0.1:18099/", f -> "", Clock.systemUTC());
        String url = google.authorizeUrl("state-1", "nonce-1", "verifier-verifier-verifier-verifier-verifier", "a@b.co");
        assertThat(url).startsWith("https://accounts.google.com/o/oauth2/v2/auth?")
                .contains("redirect_uri=http%3A%2F%2F127.0.0.1%3A18099%2Fv1%2Fdelegate%2Fauth%2Fgoogle%2Fcallback")
                .contains("scope=openid+email").contains("state=state-1").contains("nonce=nonce-1")
                .contains("code_challenge_method=S256").contains("login_hint=a%40b.co")
                .contains("code_challenge=" + GoogleSignIn.challenge("verifier-verifier-verifier-verifier-verifier"))
                .doesNotContain("verifier-verifier").doesNotContain("secret");
        // RFC 7636 appendix B.
        assertThat(GoogleSignIn.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
                .isEqualTo("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");
    }

    @Test
    void offUnlessClientIdSecretAndPublicAddressAreAllSet() {
        assertThat(new GoogleSignIn(CLIENT, "s", "http://127.0.0.1:1", f -> "", Clock.systemUTC()).enabled()).isTrue();
        assertThat(new GoogleSignIn("", "s", "http://127.0.0.1:1", f -> "", Clock.systemUTC()).enabled()).isFalse();
        assertThat(new GoogleSignIn(CLIENT, " ", "http://127.0.0.1:1", f -> "", Clock.systemUTC()).enabled()).isFalse();
        assertThat(new GoogleSignIn(CLIENT, "s", "", f -> "", Clock.systemUTC()).enabled()).isFalse();
    }
}
