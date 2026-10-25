package com.sellerops.vendor.delegate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sellerops.vendor.Codes;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * <b>A delegate joins and signs in with Google, and only the tab that started the trip can finish it</b> — contract
 * §2 (D3 revised 2026-10-26). Through the real chain (security, controllers, transactions, Flyway on H2); only
 * Google's token endpoint is a stand-in, answering for whichever account the test says chose Google.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GoogleDelegatesTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CLIENT = GoogleSignInTest.CLIENT;

    /** Who Google says signed in next; the nonce is copied from the address the browser was sent to. */
    static final class Chooser {
        String sub;
        String email;
        boolean verified = true;
        String nonce;
    }

    @TestConfiguration
    static class FakeGoogle {
        @Bean
        Chooser chooser() {
            return new Chooser();
        }

        @Bean
        @Primary
        GoogleSignIn fakeGoogle(Chooser chooser) {
            return new GoogleSignIn(CLIENT, "test-secret", "http://127.0.0.1:18099", form -> {
                assertThat(form).containsEntry("client_secret", "test-secret").containsKey("code_verifier");
                Map<String, Object> c = new LinkedHashMap<>(GoogleSignInTest.good(chooser.nonce));
                c.put("exp", java.time.Instant.now().plusSeconds(300).getEpochSecond());
                c.put("sub", chooser.sub);
                c.put("email", chooser.email);
                c.put("email_verified", chooser.verified);
                return GoogleSignInTest.token(c);
            }, Clock.systemUTC());
        }
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private Delegates delegates;
    @Autowired
    private Chooser chooser;
    @Autowired
    private JdbcClient jdbc;

    private String email;
    private String sub;

    @BeforeEach
    void setUp() {
        email = "g-" + UUID.randomUUID() + "@example.com";
        sub = "sub-" + UUID.randomUUID();
        chooser.sub = sub;
        chooser.email = email;
        chooser.verified = true;
    }

    /** start → Google → callback; answers the fragment the browser lands on. */
    private String trip(String purpose, String invite, String binding) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("purpose", purpose);
        body.put("binding", binding);
        if (invite != null) {
            body.put("inviteCode", invite);
        }
        MvcResult started = mvc.perform(post("/v1/delegate/auth/google/start").contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(body)))
                .andExpect(status().isOk()).andReturn();
        String url = JSON.readTree(started.getResponse().getContentAsString()).path("url").asText();
        chooser.nonce = param(url, "nonce");
        String location = mvc.perform(get("/v1/delegate/auth/google/callback")
                        .param("state", param(url, "state")).param("code", "google-code"))
                .andExpect(status().isFound()).andReturn().getResponse().getHeader("Location");
        assertThat(location).startsWith("/delegate#");
        return location.substring("/delegate#".length());
    }

    private static String param(String url, String name) {
        Matcher m = Pattern.compile("[?&]" + name + "=([^&]+)").matcher(url);
        assertThat(m.find()).as(name).isTrue();
        return URLDecoder.decode(m.group(1), StandardCharsets.UTF_8);
    }

    private MvcResult finish(String fragment, String binding) throws Exception {
        assertThat(fragment).startsWith("google=");
        return mvc.perform(post("/v1/delegate/auth/google/finish").contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("handoff", fragment.substring("google=".length()),
                                "binding", binding))))
                .andReturn();
    }

    private static String error(MvcResult r) throws Exception {
        return JSON.readTree(r.getResponse().getContentAsString()).path("error").asText();
    }

    @Test
    void theInvitedAddressJoinsWithGoogleAndSignsInAgainWithNoPasswordOrAuthenticator() throws Exception {
        mvc.perform(get("/v1/delegate/auth/methods")).andExpect(status().isOk()).andExpect(jsonPath("$.google").value(true));
        String invite = delegates.issueInvite(email, Delegates.Role.ADMIN);
        String binding = Codes.newCode();

        MvcResult joined = finish(trip("JOIN", invite, binding), binding);
        assertThat(joined.getResponse().getStatus()).isEqualTo(200);
        String token = JSON.readTree(joined.getResponse().getContentAsString()).path("token").asText();
        mvc.perform(get("/v1/delegate/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.role").value("ADMIN"));
        assertThat(delegates.inviteEmail(invite)).as("the invite is spent").isEmpty();
        assertThat(delegates.login(email, "", "000000", null)).as("no password path for this person").isEmpty();
        assertThat(jdbc.sql("select count(*) from delegate_google_attempts where google_sub is not null")
                .query(Integer.class).single()).as("the account Google named is not kept on the attempt").isZero();

        String again = Codes.newCode();
        MvcResult signedIn = finish(trip("LOGIN", null, again), again);
        assertThat(signedIn.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void aReturnLinkOpenedInAnotherTabFinishesNothingAndAHandoffWorksOnce() throws Exception {
        String invite = delegates.issueInvite(email, Delegates.Role.DELEGATE);
        String binding = Codes.newCode();
        String fragment = trip("JOIN", invite, binding);

        MvcResult stranger = finish(fragment, Codes.newCode());
        assertThat(stranger.getResponse().getStatus()).isEqualTo(400);
        assertThat(error(stranger)).isEqualTo("google_failed");
        // The wrong binding spent it: the right one now finds nothing, and nobody was created.
        assertThat(error(finish(fragment, binding))).isEqualTo("expired");
        assertThat(delegates.idOf(email)).isEmpty();
        assertThat(delegates.inviteEmail(invite)).as("the invite is still there to use").isPresent();

        String b2 = Codes.newCode();
        String f2 = trip("JOIN", invite, b2);
        assertThat(finish(f2, b2).getResponse().getStatus()).isEqualTo(200);
        assertThat(error(finish(f2, b2))).isEqualTo("expired");
    }

    @Test
    void anotherGoogleAccountCannotSpendTheInviteAndTheRefusalIsAnAnswerNotAnError() throws Exception {
        String invite = delegates.issueInvite(email, Delegates.Role.DELEGATE);
        chooser.email = "someone-else@example.com";
        String binding = Codes.newCode();
        MvcResult r = finish(trip("JOIN", invite, binding), binding);
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(error(r)).isEqualTo("email_mismatch");
        assertThat(delegates.inviteEmail(invite)).isPresent();

        chooser.email = email.toUpperCase();
        chooser.verified = false;
        String b2 = Codes.newCode();
        assertThat(trip("JOIN", invite, b2)).as("an unverified address").isEqualTo("google-error=google_failed");

        chooser.verified = true;
        String b3 = Codes.newCode();
        assertThat(finish(trip("JOIN", invite, b3), b3).getResponse().getStatus())
                .as("the same address in other letters").isEqualTo(200);
        String b4 = Codes.newCode();
        String other = delegates.issueInvite("second-" + email, Delegates.Role.DELEGATE);
        chooser.email = "second-" + email;
        MvcResult twice = finish(trip("JOIN", other, b4), b4);
        assertThat(twice.getResponse().getStatus()).as("one Google account, one delegate").isEqualTo(409);
    }

    @Test
    void signingInNeedsARegisteredActiveAccount() throws Exception {
        String binding = Codes.newCode();
        MvcResult unknown = finish(trip("LOGIN", null, binding), binding);
        assertThat(unknown.getResponse().getStatus()).isEqualTo(401);
        assertThat(error(unknown)).isEqualTo("not_registered");

        String invite = delegates.issueInvite(email, Delegates.Role.DELEGATE);
        String b2 = Codes.newCode();
        assertThat(finish(trip("JOIN", invite, b2), b2).getResponse().getStatus()).isEqualTo(200);
        assertThat(delegates.suspend(email)).isTrue();
        String b3 = Codes.newCode();
        assertThat(error(finish(trip("LOGIN", null, b3), b3))).isEqualTo("not_registered");
    }

    @Test
    void cancellingAtGoogleOrComingBackLateOrTwiceEndsTheTrip() throws Exception {
        String binding = Codes.newCode();
        MvcResult started = mvc.perform(post("/v1/delegate/auth/google/start").contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("purpose", "LOGIN", "binding", binding))))
                .andReturn();
        String url = JSON.readTree(started.getResponse().getContentAsString()).path("url").asText();
        String state = param(url, "state");
        assertThat(mvc.perform(get("/v1/delegate/auth/google/callback").param("state", state)
                .param("error", "access_denied")).andReturn().getResponse().getHeader("Location"))
                .isEqualTo("/delegate#google-error=cancelled");
        assertThat(mvc.perform(get("/v1/delegate/auth/google/callback").param("state", state).param("code", "c"))
                .andReturn().getResponse().getHeader("Location")).isEqualTo("/delegate#google-error=expired");
        assertThat(mvc.perform(get("/v1/delegate/auth/google/callback")).andReturn().getResponse().getHeader("Location"))
                .isEqualTo("/delegate#google-error=expired");

        MvcResult late = mvc.perform(post("/v1/delegate/auth/google/start").contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("purpose", "LOGIN", "binding", binding))))
                .andReturn();
        String lateState = param(JSON.readTree(late.getResponse().getContentAsString()).path("url").asText(), "state");
        jdbc.sql("update delegate_google_attempts set expires_at = created_at where state_hash = ?")
                .param(Codes.hash(lateState)).update();
        assertThat(mvc.perform(get("/v1/delegate/auth/google/callback").param("state", lateState).param("code", "c"))
                .andReturn().getResponse().getHeader("Location")).isEqualTo("/delegate#google-error=expired");
    }

    @Test
    void joiningNeedsALiveInvite() throws Exception {
        mvc.perform(post("/v1/delegate/auth/google/start").contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("purpose", "JOIN", "inviteCode", Codes.newCode(),
                                "binding", Codes.newCode()))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_invite"));
        mvc.perform(post("/v1/delegate/auth/google/start").contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("purpose", "LOGIN", "binding", "short"))))
                .andExpect(status().isBadRequest());
    }
}
