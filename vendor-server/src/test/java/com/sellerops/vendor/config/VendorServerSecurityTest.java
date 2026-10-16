package com.sellerops.vendor.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sellerops.vendor.account.SellerAccounts;
import com.sellerops.vendor.install.Installs;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * <b>Every route answers exactly one kind of caller</b> — contract §2, through the real chain with real credentials:
 * an invite redeemed into a session, a PC grant approved by that session and redeemed into an install token.
 *
 * <p>Each opening is paired with its refusal (no credential → 401, the other kind of credential → 403, a revoked or
 * suspended one → 401), so a chain that refused everything — or admitted everything — fails here.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class VendorServerSecurityTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final byte[] PUBLIC_KEY = new byte[32];

    static {
        for (int i = 0; i < PUBLIC_KEY.length; i++) {
            PUBLIC_KEY[i] = (byte) (i + 1);
        }
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private SellerAccounts accounts;
    @Autowired
    private Installs installs;

    private String email;
    private String session;

    @BeforeEach
    void seller() throws Exception {
        email = "seller-" + UUID.randomUUID() + "@example.com";
        String invite = accounts.issueInvite(email);
        session = body(mvc.perform(json(post("/v1/auth/invites/redeem"),
                        "{\"inviteCode\":\"" + invite + "\",\"password\":\"correct horse battery\"}"))
                .andExpect(status().isCreated())).path("token").asText();
    }

    @Test
    void healthIsOpenAndAnUnnamedRouteIsClosedToEveryone() throws Exception {
        mvc.perform(get("/health")).andExpect(status().isOk()).andExpect(content().string("ok"));
        mvc.perform(post("/health")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/anything")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/anything").with(bearer(session))).andExpect(status().isForbidden());
        mvc.perform(get("/v1/anything").with(bearer(link()))).andExpect(status().isForbidden());
    }

    @Test
    void aLinkedPcSeesItsOwnIpv4AndNobodyElseDoes() throws Exception {
        String install = link();
        mvc.perform(get("/v1/ip").with(bearer(install)).with(remote("203.0.113.7")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ipv4").value("203.0.113.7"));
        mvc.perform(get("/v1/ip").with(bearer(install)).with(remote("2001:db8::1")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("ipv4_unavailable"));
        mvc.perform(get("/v1/ip").with(remote("203.0.113.7"))).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/ip").with(bearer(session))).andExpect(status().isForbidden());
    }

    @Test
    void theTwoCredentialsNeverOpenEachOthersRoutes() throws Exception {
        String install = link();
        mvc.perform(get("/v1/installs").with(bearer(install))).andExpect(status().isForbidden());
        mvc.perform(json(post("/v1/installs/approve").with(bearer(install)), "{\"userCode\":\"BCDFGHJK\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/v1/installs/me").with(bearer(session))).andExpect(status().isForbidden());
        mvc.perform(get("/v1/installs/me").with(bearer(install))).andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceName").value("Seller Mac"))
                .andExpect(jsonPath("$.token").doesNotExist());
        mvc.perform(get("/v1/installs").with(bearer(session))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].deviceName").value("Seller Mac"));
    }

    @Test
    void aBadBearerIsRefusedEvenWhereNoCredentialIsNeeded() throws Exception {
        mvc.perform(get("/health").with(bearer("rvi_not-a-real-install-token"))).andExpect(status().isUnauthorized());
        mvc.perform(get("/health").with(bearer("not.a.jwt"))).andExpect(status().isUnauthorized());
    }

    @Test
    void theGrantHandsOverOneTokenBoundToTheKeyItWasStartedWith() throws Exception {
        JsonNode code = startGrant();
        String deviceCode = code.path("deviceCode").asText();
        mvc.perform(json(post("/v1/installs/token"), "{\"deviceCode\":\"" + deviceCode + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("authorization_pending"));
        approve(code.path("userCode").asText());
        JsonNode token = body(mvc.perform(json(post("/v1/installs/token"), "{\"deviceCode\":\"" + deviceCode + "\"}"))
                .andExpect(status().isOk()));
        assertThat(token.path("token").asText()).startsWith("rvi_");
        mvc.perform(json(post("/v1/installs/token"), "{\"deviceCode\":\"" + deviceCode + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("expired_token"));
        UUID installId = UUID.fromString(token.path("installId").asText());
        assertThat(installs.publicKey(installId)).hasValue(PUBLIC_KEY);
    }

    @Test
    void aMalformedRequestIsAnErrorNotAnAuthenticationFailure() throws Exception {
        mvc.perform(json(post("/v1/installs/code"), "{\"deviceName\":\"Seller Mac\",\"publicKey\":\"AAAA\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_public_key"));
        mvc.perform(json(post("/v1/installs/code"), "{}")).andExpect(status().isBadRequest());
    }

    @Test
    void revokingOnePcEndsThatTokenOnly() throws Exception {
        String lost = link();
        String kept = link();
        UUID lostId = UUID.fromString(body(mvc.perform(get("/v1/installs/me").with(bearer(lost)))).path("id").asText());
        mvc.perform(delete("/v1/installs/" + lostId).with(bearer(session))).andExpect(status().isNoContent());
        mvc.perform(get("/v1/ip").with(bearer(lost)).with(remote("203.0.113.7"))).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/ip").with(bearer(kept)).with(remote("203.0.113.7"))).andExpect(status().isOk());
        mvc.perform(get("/v1/installs/me").with(bearer(session))).andExpect(status().isForbidden());
    }

    @Test
    void anotherSellersPcCannotBeRevoked() throws Exception {
        String theirs = link();
        UUID theirsId = UUID.fromString(body(mvc.perform(get("/v1/installs/me").with(bearer(theirs)))).path("id").asText());
        String otherEmail = "other-" + UUID.randomUUID() + "@example.com";
        String otherSession = body(mvc.perform(json(post("/v1/auth/invites/redeem"),
                "{\"inviteCode\":\"" + accounts.issueInvite(otherEmail) + "\",\"password\":\"another long one\"}")))
                .path("token").asText();
        mvc.perform(delete("/v1/installs/" + theirsId).with(bearer(otherSession))).andExpect(status().isNotFound());
        mvc.perform(get("/v1/ip").with(bearer(theirs)).with(remote("203.0.113.7"))).andExpect(status().isOk());
    }

    @Test
    void suspensionEndsSessionsAndInstallsAndReinstatementRestoresThem() throws Exception {
        String install = link();
        assertThat(accounts.suspend(email)).isTrue();
        mvc.perform(get("/v1/installs").with(bearer(session))).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/ip").with(bearer(install)).with(remote("203.0.113.7"))).andExpect(status().isUnauthorized());
        mvc.perform(json(post("/v1/auth/login"), "{\"email\":\"" + email + "\",\"password\":\"correct horse battery\"}"))
                .andExpect(status().isUnauthorized());
        assertThat(accounts.reinstate(email)).isTrue();
        mvc.perform(get("/v1/ip").with(bearer(install)).with(remote("203.0.113.7"))).andExpect(status().isOk());
    }

    @Test
    void loginTellsNeitherWhichHalfWasWrongNorWhetherTheAccountExists() throws Exception {
        mvc.perform(json(post("/v1/auth/login"), "{\"email\":\"" + email.toUpperCase() + "\",\"password\":\"correct horse battery\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.token").isNotEmpty());
        mvc.perform(json(post("/v1/auth/login"), "{\"email\":\"" + email + "\",\"password\":\"wrong password!\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("invalid_login"));
        mvc.perform(json(post("/v1/auth/login"), "{\"email\":\"nobody@example.com\",\"password\":\"wrong password!\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("invalid_login"));
    }

    @Test
    void theApprovalPageIsOpenButCannotBeFramedOrLeakItsCode() throws Exception {
        mvc.perform(get("/link").param("code", "BCDF-GHJK"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .forwardedUrl("/link/index.html"));
        mvc.perform(get("/link/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("연결 허용")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Content-Security-Policy", org.hamcrest.Matchers.containsString("frame-ancestors 'none'")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("X-Frame-Options", "DENY"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Referrer-Policy", "no-referrer"));
        mvc.perform(get("/link/link.js")).andExpect(status().isOk());
        mvc.perform(get("/link/other.html")).andExpect(status().isNotFound());
    }

    @Test
    void theCodeIsPrefilledOnlyForABrowserOnTheSameNetworkAsThePc() throws Exception {
        JsonNode same = startGrantFrom("198.51.100.20");
        mvc.perform(json(post("/v1/installs/pending").with(bearer(session)).with(remote("198.51.100.20")),
                        "{\"userCode\":\"" + same.path("userCode").asText() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceName").value("Seller Mac"))
                .andExpect(jsonPath("$.sameNetwork").value(true));
        JsonNode elsewhere = startGrantFrom("198.51.100.20");
        mvc.perform(json(post("/v1/installs/pending").with(bearer(session)).with(remote("203.0.113.99")),
                        "{\"userCode\":\"" + elsewhere.path("userCode").asText() + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sameNetwork").value(false));
        JsonNode v6 = startGrantFrom("2001:db8::7");
        mvc.perform(json(post("/v1/installs/pending").with(bearer(session)).with(remote("2001:db8::7")),
                        "{\"userCode\":\"" + v6.path("userCode").asText() + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sameNetwork").value(false));
        mvc.perform(json(post("/v1/installs/pending").with(bearer(session)), "{\"userCode\":\"ZZZZZZZZ\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(json(post("/v1/installs/pending").with(bearer(link())),
                        "{\"userCode\":\"" + same.path("userCode").asText() + "\"}"))
                .andExpect(status().isForbidden());
        approve(same.path("userCode").asText());
    }

    // --- helpers ---------------------------------------------------------------------------------------------------

    /** A PC grant approved by this test's seller, redeemed into an install token. */
    private String link() throws Exception {
        JsonNode code = startGrant();
        approve(code.path("userCode").asText());
        return body(mvc.perform(json(post("/v1/installs/token"),
                "{\"deviceCode\":\"" + code.path("deviceCode").asText() + "\"}"))
                .andExpect(status().isOk())).path("token").asText();
    }

    private JsonNode startGrantFrom(String address) throws Exception {
        String key = Base64.getEncoder().encodeToString(PUBLIC_KEY);
        return body(mvc.perform(json(post("/v1/installs/code").with(remote(address)),
                        "{\"deviceName\":\"Seller Mac\",\"publicKey\":\"" + key + "\"}"))
                .andExpect(status().isOk()));
    }

    private JsonNode startGrant() throws Exception {
        String key = Base64.getEncoder().encodeToString(PUBLIC_KEY);
        return body(mvc.perform(json(post("/v1/installs/code"),
                        "{\"deviceName\":\"Seller Mac\",\"appVersion\":\"0.1.0\",\"publicKey\":\"" + key + "\"}"))
                .andExpect(status().isOk()));
    }

    private void approve(String userCode) throws Exception {
        String dashed = userCode.substring(0, 4) + "-" + userCode.substring(4).toLowerCase();
        mvc.perform(json(post("/v1/installs/approve").with(bearer(session)), "{\"userCode\":\"" + dashed + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.deviceName").value("Seller Mac"));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor bearer(String token) {
        return r -> {
            r.addHeader("Authorization", "Bearer " + token);
            return r;
        };
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor remote(String address) {
        return r -> {
            r.setRemoteAddr(address);
            return r;
        };
    }

    private static JsonNode body(org.springframework.test.web.servlet.ResultActions actions) throws Exception {
        return JSON.readTree(actions.andReturn().getResponse().getContentAsString());
    }
}
