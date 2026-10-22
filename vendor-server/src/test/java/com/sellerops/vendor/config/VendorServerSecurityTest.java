package com.sellerops.vendor.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sellerops.vendor.TestTotp;
import com.sellerops.vendor.account.SellerAccounts;
import com.sellerops.vendor.auth.SessionRevocations;
import com.sellerops.vendor.auth.SessionTokens;
import com.sellerops.vendor.delegate.Delegates;
import com.sellerops.vendor.delegate.OtpQrTest;
import com.sellerops.vendor.install.Installs;
import com.sellerops.vendor.mail.DevOutboxMailer;
import com.sellerops.vendor.mail.Mailer;
import com.sellerops.vendor.mail.OutboundMail;
import com.sellerops.vendor.review.ExportWorkbooks;
import com.sellerops.vendor.store.CheckedStores;
import com.sellerops.vendor.store.Stores;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
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
    @Autowired
    private Delegates delegates;
    @Autowired
    private Stores stores;
    @Autowired
    private Mailer mailer;
    @Autowired
    private org.springframework.jdbc.core.simple.JdbcClient jdbc;
    @Autowired
    private SessionRevocations revocations;

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

    /**
     * The join page shows the invited address before a password is chosen; the redeem answer carries the phone page's
     * address and its QR. A spent or unknown code names nobody.
     */
    @Test
    void anInviteNamesItsAccountUntilItIsSpentAndTheNewAccountGetsItsPhoneAddress() throws Exception {
        String invited = "join-" + UUID.randomUUID() + "@example.com";
        String code = accounts.issueInvite(invited);
        mvc.perform(json(post("/v1/auth/invites/peek"), "{\"inviteCode\":\"" + code + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value(invited));
        JsonNode redeemed = body(mvc.perform(json(post("/v1/auth/invites/redeem"),
                        "{\"inviteCode\":\"" + code + "\",\"password\":\"correct horse battery\"}"))
                .andExpect(status().isCreated()));
        assertThat(redeemed.path("token").asText()).isNotBlank();
        assertThat(redeemed.path("email").asText()).isEqualTo(invited);
        assertThat(redeemed.path("phoneUrl").asText()).isEqualTo("http://localhost/replies");
        assertThat(redeemed.path("phoneQr").size()).isGreaterThan(20);
        mvc.perform(json(post("/v1/auth/invites/peek"), "{\"inviteCode\":\"" + code + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_invite"));
        mvc.perform(json(post("/v1/auth/invites/peek"), "{\"inviteCode\":\"not-a-code\"}"))
                .andExpect(status().isBadRequest());
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
    void aNewPcIsApprovedOnlyWithTheMailedCodeOfThatSessionForThatPc() throws Exception {
        String userCode = startGrant().path("userCode").asText();
        String approveBody = "{\"userCode\":\"" + userCode + "\"}";
        mvc.perform(json(post("/v1/installs/approve").with(bearer(session)), approveBody))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("step_up_required"));

        mvc.perform(json(post("/v1/installs/step-up").with(bearer(session)), approveBody)).andExpect(status().isOk())
                .andExpect(jsonPath("$.sentTo").value(email.substring(0, 2) + "***" + email.substring(email.indexOf('@'))));
        OutboundMail codeMail = lastMailTo(email);
        assertThat(codeMail.subject()).startsWith("[reviewnary] PC 연결 확인 코드");
        assertThat(codeMail.text()).contains("PC 종류: Seller Mac").doesNotContain("http");
        String code = mailedCode(email);
        assertThat(verifiedAccounts()).as("a mailed code proves nothing until it comes back").isZero();

        String other = body(mvc.perform(json(post("/v1/auth/login"),
                "{\"email\":\"" + email + "\",\"password\":\"correct horse battery\"}"))).path("token").asText();
        mvc.perform(json(post("/v1/installs/step-up/verify").with(bearer(other)),
                        "{\"userCode\":\"" + userCode + "\",\"code\":\"" + code + "\"}"))
                .andExpect(status().isGone()).andExpect(jsonPath("$.error").value("expired"));
        String wrong = code.equals("000000") ? "111111" : "000000";
        mvc.perform(json(post("/v1/installs/step-up/verify").with(bearer(session)),
                        "{\"userCode\":\"" + userCode + "\",\"code\":\"" + wrong + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("wrong_code"))
                .andExpect(jsonPath("$.left").value(4));
        mvc.perform(json(post("/v1/installs/step-up/verify").with(bearer(session)),
                        "{\"userCode\":\"" + userCode + "\",\"code\":\"" + code + "\"}"))
                .andExpect(status().isNoContent());
        assertThat(verifiedAccounts()).isEqualTo(1);

        String secondPc = startGrant().path("userCode").asText();
        // The code was for the first PC only, and for this session only.
        mvc.perform(json(post("/v1/installs/approve").with(bearer(session)), "{\"userCode\":\"" + secondPc + "\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(json(post("/v1/installs/approve").with(bearer(other)), approveBody))
                .andExpect(status().isForbidden());
        mvc.perform(json(post("/v1/installs/approve").with(bearer(session)), approveBody)).andExpect(status().isOk());
        OutboundMail notice = lastMailTo(email);
        assertThat(notice.subject()).isEqualTo("[reviewnary] 새 PC가 연결됐습니다");
        assertThat(notice.text()).contains("PC 종류: Seller Mac", "(한국 시간)", "운영자").doesNotContain("http");
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
                .andExpect(jsonPath("$.accountEmail").value(email))
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
    void loggingOutEndsThatOneSessionAndEndingAllEndsEveryOneButNotTheAccountOrItsPcs() throws Exception {
        String install = link();
        String phone = sellerLogin();
        mvc.perform(post("/v1/auth/logout")).andExpect(status().isUnauthorized());
        mvc.perform(post("/v1/delegate/auth/logout").with(bearer(session))).andExpect(status().isForbidden());

        mvc.perform(post("/v1/auth/logout").with(bearer(session))).andExpect(status().isNoContent());
        // The same token, wherever a copy of it was left.
        mvc.perform(get("/v1/stores").with(bearer(session))).andExpect(status().isUnauthorized());
        mvc.perform(post("/v1/auth/logout").with(bearer(session))).andExpect(status().isUnauthorized());
        // The seller's other session (the phone) is not this one.
        mvc.perform(get("/v1/stores").with(bearer(phone))).andExpect(status().isOk());

        assertThat(revocations.endAll(SessionTokens.Kind.SELLER, accounts.idOf(email).orElseThrow())).isTrue();
        mvc.perform(get("/v1/stores").with(bearer(phone))).andExpect(status().isUnauthorized());
        // A PC's install token is not a session, and the account is untouched: signing in again works.
        mvc.perform(get("/v1/stores").with(bearer(install))).andExpect(status().isOk());
        mvc.perform(get("/v1/stores").with(bearer(sellerLogin()))).andExpect(status().isOk());
    }

    @Test
    void aDelegateLogsOutOneSessionAndEndingAllEndsTheRest() throws Exception {
        Delegate me = enrolDelegate();
        String second = body(mvc.perform(json(post("/v1/delegate/auth/login"),
                login(me.email(), "correct horse battery", TestTotp.code(me.secret(), Instant.now(), 1))))
                .andExpect(status().isOk())).path("token").asText();
        mvc.perform(post("/v1/auth/logout").with(bearer(second))).andExpect(status().isForbidden());
        mvc.perform(post("/v1/delegate/auth/logout").with(bearer(second))).andExpect(status().isNoContent());
        mvc.perform(get("/v1/delegate/me").with(bearer(second))).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/delegate/me").with(bearer(me.token()))).andExpect(status().isOk());

        assertThat(revocations.endAll(SessionTokens.Kind.DELEGATE, me.id())).isTrue();
        mvc.perform(get("/v1/delegate/me").with(bearer(me.token()))).andExpect(status().isUnauthorized());
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
    void theSellerAndDelegatePagesAreOpenUnframeableAndShowImagesOnlyFromNaver() throws Exception {
        mvc.perform(get("/replies")).andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .forwardedUrl("/replies/index.html"));
        mvc.perform(get("/delegate")).andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .forwardedUrl("/delegate/index.html"));
        for (String page : List.of("/replies/index.html", "/delegate/index.html")) {
            mvc.perform(get(page))
                    .andExpect(status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                            .string("Content-Security-Policy", org.hamcrest.Matchers.allOf(
                                    org.hamcrest.Matchers.containsString("default-src 'self'"),
                                    org.hamcrest.Matchers.containsString("img-src 'self' https://phinf.pstatic.net;"),
                                    org.hamcrest.Matchers.containsString("frame-ancestors 'none'"),
                                    org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("unsafe")))))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                            .string("X-Frame-Options", "DENY"));
        }
        for (String asset : List.of("/replies/replies.js", "/delegate/delegate.js", "/assets/app.css")) {
            mvc.perform(get(asset)).andExpect(status().isOk());
        }
        mvc.perform(get("/delegate/join")).andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .forwardedUrl("/delegate/join.html"));
        mvc.perform(get("/delegate/join.html")).andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Content-Security-Policy", org.hamcrest.Matchers.allOf(
                                org.hamcrest.Matchers.containsString("default-src 'self'"),
                                org.hamcrest.Matchers.containsString("frame-ancestors 'none'"))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Referrer-Policy", "no-referrer"));
        mvc.perform(get("/delegate/join.js")).andExpect(status().isOk());
        mvc.perform(get("/join")).andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .forwardedUrl("/join/index.html"));
        mvc.perform(get("/join/index.html")).andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Content-Security-Policy", org.hamcrest.Matchers.containsString("default-src 'self'")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Referrer-Policy", "no-referrer"));
        mvc.perform(get("/join/join.js")).andExpect(status().isOk());
        mvc.perform(get("/delegate/other.html")).andExpect(status().isNotFound());
        mvc.perform(get("/v1/delegate/tasks")).andExpect(status().isUnauthorized());
    }

    /**
     * The pages run under {@code default-src 'self'}, so inline code would simply not run — but a page that needed it
     * would be "fixed" by loosening the policy. And every server value is customer or seller text: it is written as
     * text, never parsed as HTML.
     */
    @Test
    void thePagesCarryNoInlineCodeAndNeverWriteServerTextAsHtml() throws Exception {
        java.nio.file.Path root = java.nio.file.Path.of("src/main/resources/static");
        List<java.nio.file.Path> files;
        try (var walk = java.nio.file.Files.walk(root)) {
            files = walk.filter(java.nio.file.Files::isRegularFile).toList();
        }
        List<java.nio.file.Path> html = files.stream().filter(f -> f.toString().endsWith(".html")).toList();
        List<java.nio.file.Path> js = files.stream().filter(f -> f.toString().endsWith(".js")).toList();
        org.assertj.core.api.Assertions.assertThat(html).hasSizeGreaterThanOrEqualTo(3);
        org.assertj.core.api.Assertions.assertThat(js).hasSizeGreaterThanOrEqualTo(3);
        java.util.regex.Pattern inline = java.util.regex.Pattern.compile(
                "<script(?![^>]*\\ssrc=)[^>]*>|\\sstyle=|\\son[a-z]+=|<style", java.util.regex.Pattern.CASE_INSENSITIVE);
        for (java.nio.file.Path f : html) {
            String text = java.nio.file.Files.readString(f);
            org.assertj.core.api.Assertions.assertThat(inline.matcher(text).find()).as(f.toString()).isFalse();
        }
        java.util.regex.Pattern asHtml = java.util.regex.Pattern.compile(
                "innerHTML|outerHTML|insertAdjacentHTML|document\\.write|\\beval\\(|new Function");
        for (java.nio.file.Path f : js) {
            String text = java.nio.file.Files.readString(f);
            org.assertj.core.api.Assertions.assertThat(asHtml.matcher(text).find()).as(f.toString()).isFalse();
        }
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

    @Test
    void aDelegateEnrolsOverHttpAndSeesOnlyTheirOwnRoute() throws Exception {
        Delegate me = enrolDelegate();
        JsonNode body = body(mvc.perform(get("/v1/delegate/me").with(bearer(me.token()))).andExpect(status().isOk()));
        assertThat(body.path("email").asText()).isEqualTo(me.email());
        assertThat(body.path("role").asText()).isEqualTo("DELEGATE");
        assertThat(body.path("unusedRecoveryCodes").asInt()).isEqualTo(Delegates.RECOVERY_CODES);
        assertThat(body.path("stores")).isEmpty();

        mvc.perform(get("/v1/delegate/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/delegate/me").with(bearer(session))).andExpect(status().isForbidden());
        mvc.perform(get("/v1/delegate/me").with(bearer(link()))).andExpect(status().isForbidden());
        // …and a delegate session opens no seller or PC route.
        mvc.perform(get("/v1/installs").with(bearer(me.token()))).andExpect(status().isForbidden());
        mvc.perform(get("/v1/stores").with(bearer(me.token()))).andExpect(status().isForbidden());
        mvc.perform(get("/v1/ip").with(bearer(me.token())).with(remote("203.0.113.7"))).andExpect(status().isForbidden());
    }

    @Test
    void aDelegateSeesAStoreOnlyWhileAssignedAndASellerOnlyTheirOwn() throws Exception {
        Delegate me = enrolDelegate();
        UUID account = accounts.idOf(email).orElseThrow();
        UUID store = stores.add(account, Stores.Channel.NAVER, "데모 스토어");
        UUID elsewhere = stores.add(accounts.redeem(accounts.issueInvite("x-" + email), "another long one"),
                Stores.Channel.NAVER, "남의 스토어");
        stores.assign(store, me.id());
        CheckedStores.checked(jdbc, store, me.id());
        mvc.perform(get("/v1/delegate/me").with(bearer(me.token()))).andExpect(status().isOk())
                .andExpect(jsonPath("$.stores.length()").value(1))
                .andExpect(jsonPath("$.stores[0].id").value(store.toString()))
                .andExpect(jsonPath("$.stores[0].label").value("데모 스토어"));
        stores.unassign(store, me.id());
        mvc.perform(get("/v1/delegate/me").with(bearer(me.token()))).andExpect(jsonPath("$.stores.length()").value(0));

        mvc.perform(get("/v1/stores").with(bearer(session))).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(store.toString()))
                .andExpect(jsonPath("$[0].channel").value("NAVER"));
        mvc.perform(get("/v1/stores").with(bearer(link()))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(store.toString()))
                .andExpect(jsonPath("$[0].lastUploadAt").value(org.hamcrest.Matchers.nullValue()));
        mvc.perform(get("/v1/stores")).andExpect(status().isUnauthorized());
        assertThat(elsewhere).isNotEqualTo(store);
    }

    @Test
    void aDelegateSignInFailsAloneOnEitherFactorAndASuspendedDelegateIsOutAtTheNextRequest() throws Exception {
        Delegate me = enrolDelegate();
        String nextCode = TestTotp.code(me.secret(), Instant.now(), 1);
        mvc.perform(json(post("/v1/delegate/auth/login"), login(me.email(), "wrong password!!", nextCode)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("invalid_login"));
        mvc.perform(json(post("/v1/delegate/auth/login"), "{\"email\":\"" + me.email()
                        + "\",\"password\":\"correct horse battery\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("invalid_login"));
        String token = body(mvc.perform(json(post("/v1/delegate/auth/login"),
                login(me.email(), "correct horse battery", nextCode))).andExpect(status().isOk()))
                .path("token").asText();
        // A seller's login is not a delegate's, even with the same password.
        mvc.perform(json(post("/v1/auth/login"), "{\"email\":\"" + me.email()
                        + "\",\"password\":\"correct horse battery\"}"))
                .andExpect(status().isUnauthorized());

        assertThat(delegates.suspend(me.email())).isTrue();
        mvc.perform(get("/v1/delegate/me").with(bearer(token))).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/delegate/me").with(bearer(me.token()))).andExpect(status().isUnauthorized());
    }

    @Test
    void enrolmentErrorsAreFixedCodes() throws Exception {
        mvc.perform(json(post("/v1/delegate/auth/invites/stage"),
                        "{\"inviteCode\":\"nope\",\"password\":\"correct horse battery\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_invite"));
        String invite = delegates.issueInvite("e-" + email, Delegates.Role.DELEGATE);
        mvc.perform(json(post("/v1/delegate/auth/invites/confirm"),
                        "{\"inviteCode\":\"" + invite + "\",\"otp\":\"123456\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("not_staged"));
    }

    @Test
    void anAssignedDelegateUploadsAndOnlyThatSellersPcPullsTheResult() throws Exception {
        Delegate me = enrolDelegate();
        UUID store = stores.add(accounts.idOf(email).orElseThrow(), Stores.Channel.NAVER, "데모 스토어");
        String uploadPath = "/v1/delegate/stores/" + store + "/review-uploads";
        MockMultipartFile file = new MockMultipartFile("file", "export.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", ExportWorkbooks.fixture());

        mvc.perform(multipart(uploadPath).file(file).with(bearer(me.token()))).andExpect(status().isNotFound());
        stores.assign(store, me.id());
        // Assigned but not yet checked (V9): no seller's reviews are taken, the same answer as no assignment.
        mvc.perform(multipart(uploadPath).file(file).with(bearer(me.token()))).andExpect(status().isNotFound());
        CheckedStores.checked(jdbc, store, me.id());
        mvc.perform(multipart(uploadPath).file(file).with(bearer(me.token()))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.added").value(6)).andExpect(jsonPath("$.attachmentsDropped").value(1));
        mvc.perform(multipart(uploadPath).file(file).with(bearer(session))).andExpect(status().isForbidden());
        mvc.perform(multipart(uploadPath).file(file)).andExpect(status().isUnauthorized());
        mvc.perform(multipart(uploadPath).file(new MockMultipartFile("file", "x.csv", "text/csv",
                        "a,b\n".getBytes())).with(bearer(me.token())))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("not_xlsx"));

        String pc = link();
        mvc.perform(get("/v1/stores").with(bearer(pc))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].lastUploadAt").isNotEmpty());
        String feed = "/v1/stores/" + store + "/reviews";
        JsonNode page = body(mvc.perform(get(feed).with(bearer(pc))).andExpect(status().isOk()));
        assertThat(page.path("events")).hasSize(6);
        assertThat(page.path("events").get(0).path("kind").asText()).isEqualTo("NEW");
        assertThat(page.path("events").get(0).path("review").path("reviewNo").asText()).isEqualTo("1000000001");
        assertThat(page.toString()).doesNotContain("MUST-NOT-PERSIST");
        mvc.perform(json(post(feed + "/ack").with(bearer(pc)), "{\"cursor\":" + page.path("cursor").asLong() + "}"))
                .andExpect(status().isNoContent());
        String reviewId = page.path("events").get(0).path("review").path("id").asText();
        mvc.perform(json(post(feed + "/" + reviewId + "/disposition").with(bearer(session)),
                        "{\"noReplyNeeded\":true,\"commandId\":\"c-1\"}"))
                .andExpect(status().isNoContent());

        // The seller's session cannot pull (the PC does), a delegate cannot pull, and another seller sees nothing.
        mvc.perform(get(feed).with(bearer(session))).andExpect(status().isForbidden());
        mvc.perform(get(feed).with(bearer(me.token()))).andExpect(status().isForbidden());
        String otherSession = body(mvc.perform(json(post("/v1/auth/invites/redeem"),
                "{\"inviteCode\":\"" + accounts.issueInvite("y-" + email) + "\",\"password\":\"another long one\"}")))
                .path("token").asText();
        mvc.perform(json(post(feed + "/" + reviewId + "/disposition").with(bearer(otherSession)),
                        "{\"noReplyNeeded\":true,\"commandId\":\"c-2\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void theWholeLoopRunsOverHttpWithEachStepOpenToExactlyOneKindOfCaller() throws Exception {
        Delegate me = enrolDelegate();
        UUID store = stores.add(accounts.idOf(email).orElseThrow(), Stores.Channel.NAVER, "데모 스토어");
        stores.assign(store, me.id());
        CheckedStores.checked(jdbc, store, me.id());
        mvc.perform(multipart("/v1/delegate/stores/" + store + "/review-uploads").file(new MockMultipartFile("file",
                "export.xlsx", "application/octet-stream", ExportWorkbooks.fixture())).with(bearer(me.token())))
                .andExpect(status().isCreated());
        String pc = link();
        JsonNode review = body(mvc.perform(get("/v1/stores/" + store + "/reviews").with(bearer(pc))))
                .path("events").get(0).path("review");

        // 1. The PC drafts. Nobody else may.
        String draft = "{\"reviewId\":\"" + review.path("id").asText() + "\",\"body\":\"소중한 리뷰 감사합니다.\","
                + "\"meta\":{\"reviewBodyFingerprint\":\"" + review.path("bodyFingerprint").asText()
                + "\",\"generatedAt\":\"2026-10-20T00:00:00Z\",\"knowledgeFp\":\"k1\",\"policyFp\":\"p1\","
                + "\"voiceFp\":\"v1\"}}";
        String tasksPath = "/v1/stores/" + store + "/tasks";
        mvc.perform(json(post(tasksPath).with(bearer(session)), draft)).andExpect(status().isForbidden());
        mvc.perform(json(post(tasksPath).with(bearer(me.token())), draft)).andExpect(status().isForbidden());
        JsonNode task = body(mvc.perform(json(post(tasksPath).with(bearer(pc)), draft)).andExpect(status().isCreated()));
        String taskPath = "/v1/tasks/" + task.path("id").asText();
        assertThat(task.path("state").asText()).isEqualTo("AWAITING_APPROVAL");

        // 2. The seller approves that exact version from the phone, reading the review from the task alone. A delegate
        // may not.
        mvc.perform(get(taskPath).with(bearer(session))).andExpect(status().isOk())
                .andExpect(jsonPath("$.review.body").value(review.path("body").asText()))
                .andExpect(jsonPath("$.review.rating").value(review.path("rating").asInt()))
                .andExpect(jsonPath("$.review.productName").value(review.path("productName").asText()))
                .andExpect(jsonPath("$.review.displayState").value(review.path("displayState").asText()));
        String approve = "{\"version\":1,\"fingerprint\":\"" + task.path("draftFingerprint").asText()
                + "\",\"commandId\":\"approve-1\"}";
        mvc.perform(json(post(taskPath + "/approve").with(bearer(me.token())), approve)).andExpect(status().isForbidden());
        mvc.perform(json(post(taskPath + "/approve").with(bearer(session)), approve)).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("APPROVED"));
        mvc.perform(json(post(taskPath + "/approve").with(bearer(session)), approve)).andExpect(status().isOk());

        // 3. The delegate claims, looks again, posts by hand and reports. A PC or seller may not.
        JsonNode listed = body(mvc.perform(get("/v1/delegate/tasks").with(bearer(me.token()))).andExpect(status().isOk()))
                .get(0);
        assertThat(listed.path("approvedBody").asText()).isEqualTo("소중한 리뷰 감사합니다.");
        String claimPath = "/v1/delegate/tasks/" + task.path("id").asText();
        String claim = "{\"stateVersion\":" + listed.path("stateVersion").asInt() + "}";
        mvc.perform(json(post(claimPath + "/claim").with(bearer(pc)), claim)).andExpect(status().isForbidden());
        mvc.perform(json(post(claimPath + "/claim").with(bearer(me.token())), claim)).andExpect(status().isOk());
        mvc.perform(json(post(claimPath + "/claim").with(bearer(me.token())), claim)).andExpect(status().isConflict());
        mvc.perform(get(claimPath + "/preflight").with(bearer(me.token()))).andExpect(status().isOk())
                .andExpect(jsonPath("$.clearToPost").value(true));
        String report = "{\"outcome\":\"POSTED\",\"postedFingerprint\":\"" + task.path("draftFingerprint").asText()
                + "\",\"checkedAt\":\"2026-10-20T00:05:00Z\",\"method\":\"SELLER_CENTER_SCREEN\","
                + "\"commandId\":\"report-1\"}";
        mvc.perform(json(post(claimPath + "/report").with(bearer(session)), report)).andExpect(status().isForbidden());
        mvc.perform(json(post(claimPath + "/report").with(bearer(me.token())), report)).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("POSTED"));

        // 4. The PC syncs the end; the seller reads it; another seller sees nothing of it.
        JsonNode changes = body(mvc.perform(get(tasksPath + "/changes").with(bearer(pc))).andExpect(status().isOk()));
        assertThat(changes.path("changes").get(changes.path("changes").size() - 1).path("state").asText())
                .isEqualTo("POSTED");
        mvc.perform(get(taskPath).with(bearer(session))).andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("POSTED")).andExpect(jsonPath("$.claimed").value(true));
        String otherSession = body(mvc.perform(json(post("/v1/auth/invites/redeem"),
                "{\"inviteCode\":\"" + accounts.issueInvite("z-" + email) + "\",\"password\":\"another long one\"}")))
                .path("token").asText();
        mvc.perform(get(taskPath).with(bearer(otherSession))).andExpect(status().isNotFound());
        mvc.perform(get(tasksPath).with(bearer(otherSession))).andExpect(status().isNotFound());
    }

    /** Owner decision 2026-10-23: only the PC holds an approved reply, and the delegate is told why before saving. */
    @Test
    void onlyThePcHoldsAnApprovedReplyAndTheDelegateIsToldWhy() throws Exception {
        Delegate me = enrolDelegate();
        UUID store = stores.add(accounts.idOf(email).orElseThrow(), Stores.Channel.NAVER, "데모 스토어");
        stores.assign(store, me.id());
        CheckedStores.checked(jdbc, store, me.id());
        mvc.perform(multipart("/v1/delegate/stores/" + store + "/review-uploads").file(new MockMultipartFile("file",
                "export.xlsx", "application/octet-stream", ExportWorkbooks.fixture())).with(bearer(me.token())))
                .andExpect(status().isCreated());
        String pc = link();
        JsonNode review = body(mvc.perform(get("/v1/stores/" + store + "/reviews").with(bearer(pc))))
                .path("events").get(0).path("review");
        String draft = "{\"reviewId\":\"" + review.path("id").asText() + "\",\"body\":\"세탁 후 수축이면 무료 교환해 "
                + "드리겠습니다.\",\"meta\":{\"reviewBodyFingerprint\":\"" + review.path("bodyFingerprint").asText()
                + "\",\"generatedAt\":\"2026-10-20T00:00:00Z\",\"knowledgeFp\":\"k1\",\"policyFp\":\"p1\","
                + "\"voiceFp\":\"v1\"}}";
        JsonNode task = body(mvc.perform(json(post("/v1/stores/" + store + "/tasks").with(bearer(pc)), draft))
                .andExpect(status().isCreated()));
        String taskPath = "/v1/tasks/" + task.path("id").asText();
        mvc.perform(json(post(taskPath + "/approve").with(bearer(session)), "{\"version\":1,\"fingerprint\":\""
                + task.path("draftFingerprint").asText() + "\",\"commandId\":\"approve-h\"}"))
                .andExpect(status().isOk());
        String delegatePath = "/v1/delegate/tasks/" + task.path("id").asText();
        mvc.perform(json(post(delegatePath + "/claim").with(bearer(me.token())), "{\"stateVersion\":1}"))
                .andExpect(status().isOk());

        String check = "{\"approvedVersion\":1,\"hold\":true,\"commandId\":\"check-1\",\"note\":{"
                + "\"codes\":[\"UNSUPPORTED_PROMISE\"],\"sources\":[{\"kind\":\"ORG_POLICY\",\"title\":\"교환 안내\","
                + "\"changedAt\":\"2026-10-20T00:30:00Z\",\"removed\":false}],\"promises\":[\"교환\"],"
                + "\"sentences\":[\"세탁 후 수축이면 무료 교환해 드리겠습니다.\"]}}";
        mvc.perform(json(post(taskPath + "/approved-check").with(bearer(session)), check))
                .andExpect(status().isForbidden());
        mvc.perform(json(post(taskPath + "/approved-check").with(bearer(me.token())), check))
                .andExpect(status().isForbidden());
        mvc.perform(json(post(taskPath + "/approved-check").with(bearer(pc)), check)).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("HOLD_REQUESTED"))
                .andExpect(jsonPath("$.basisNote.sources[0].changedAt").value("2026-10-20T00:30:00Z"));

        mvc.perform(get(delegatePath + "/preflight").with(bearer(me.token()))).andExpect(status().isOk())
                .andExpect(jsonPath("$.clearToPost").value(false))
                .andExpect(jsonPath("$.holdRequested").value(true))
                .andExpect(jsonPath("$.holdNote.promises[0]").value("교환"));
        mvc.perform(json(post(delegatePath + "/report").with(bearer(me.token())), "{\"outcome\":\"FAILED\","
                + "\"checkedAt\":\"2026-10-20T00:40:00Z\",\"method\":\"SELLER_CENTER_SCREEN\",\"commandId\":\"r-h\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("HELD"));
        mvc.perform(get(taskPath).with(bearer(session))).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("HELD"))
                .andExpect(jsonPath("$.basisNote.sentences[0]").value("세탁 후 수축이면 무료 교환해 드리겠습니다."));
    }

    // --- helpers ---------------------------------------------------------------------------------------------------

    private record Delegate(UUID id, String email, String secret, String token) {}

    /** A delegate invited, staged and confirmed through the real routes. */
    private Delegate enrolDelegate() throws Exception {
        String address = "delegate-" + UUID.randomUUID() + "@example.com";
        String invite = delegates.issueInvite(address, Delegates.Role.DELEGATE);
        JsonNode staged = body(mvc.perform(json(post("/v1/delegate/auth/invites/stage"),
                        "{\"inviteCode\":\"" + invite + "\",\"password\":\"correct horse battery\"}"))
                .andExpect(status().isOk()));
        String secret = staged.path("secret").asText();
        // The join page draws the QR the server encoded — of the same URI, nothing else.
        assertThat(staged.path("qr")).isNotEmpty();
        assertThat(OtpQrTest.decode(JSON.convertValue(staged.path("qr"), String[].class)))
                .isEqualTo(staged.path("otpauthUri").asText());
        JsonNode enrolled = body(mvc.perform(json(post("/v1/delegate/auth/invites/confirm"),
                        "{\"inviteCode\":\"" + invite + "\",\"otp\":\"" + TestTotp.code(secret, Instant.now(), 0)
                                + "\"}"))
                .andExpect(status().isCreated()));
        assertThat(enrolled.path("recoveryCodes")).hasSize(Delegates.RECOVERY_CODES);
        return new Delegate(delegates.idOf(address).orElseThrow(), address, secret, enrolled.path("token").asText());
    }

    private String sellerLogin() throws Exception {
        return body(mvc.perform(json(post("/v1/auth/login"),
                "{\"email\":\"" + email + "\",\"password\":\"correct horse battery\"}"))
                .andExpect(status().isOk())).path("token").asText();
    }

    private static String login(String email, String password, String otp) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\",\"otp\":\"" + otp + "\"}";
    }


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
        stepUp(session, email, dashed);
        mvc.perform(json(post("/v1/installs/approve").with(bearer(session)), "{\"userCode\":\"" + dashed + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.deviceName").value("Seller Mac"));
    }

    /** The step-up a PC approval takes (§2): a code mailed to {@code to}, entered by this session for this PC. */
    private void stepUp(String bearerToken, String to, String userCode) throws Exception {
        mvc.perform(json(post("/v1/installs/step-up").with(bearer(bearerToken)), "{\"userCode\":\"" + userCode + "\"}"))
                .andExpect(status().isOk());
        mvc.perform(json(post("/v1/installs/step-up/verify").with(bearer(bearerToken)),
                        "{\"userCode\":\"" + userCode + "\",\"code\":\"" + mailedCode(to) + "\"}"))
                .andExpect(status().isNoContent());
    }

    private int verifiedAccounts() {
        return jdbc.sql("select count(*) from seller_accounts where email = ? and email_verified_at is not null")
                .param(email).query(Integer.class).single();
    }

    private OutboundMail lastMailTo(String to) {
        List<OutboundMail> mails = ((DevOutboxMailer) mailer).outbox().stream().filter(m -> m.to().equals(to)).toList();
        return mails.get(mails.size() - 1);
    }

    private String mailedCode(String to) {
        List<OutboundMail> mails = ((DevOutboxMailer) mailer).outbox().stream().filter(m -> m.to().equals(to)).toList();
        Matcher m = Pattern.compile("PC 연결 확인 코드: (\\d{6})").matcher(mails.get(mails.size() - 1).text());
        assertThat(m.find()).isTrue();
        return m.group(1);
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
