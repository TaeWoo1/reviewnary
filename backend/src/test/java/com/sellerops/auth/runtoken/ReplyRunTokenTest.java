package com.sellerops.auth.runtoken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sellerops.auth.AuthPrincipal;
import com.sellerops.organization.Organization;
import com.sellerops.organization.OrganizationRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

/**
 * <b>A run token opens one run's routes, for minutes, until the run ends</b> (owner, 2026-10-28,
 * {@code docs/standing/reply_agent_v1.md} §2) — never a seller's, never another route, never after.
 */
@DataJpaTest
@ActiveProfiles("test")
class ReplyRunTokenTest {

    @Autowired ReplyAgentRunRepository runs;
    @Autowired OrganizationRepository organizations;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private UUID org() {
        Organization o = new Organization();
        o.setName("합성 판매자");
        return organizations.save(o).getId();
    }

    private ReplyRunTokenAuthFilter filter(ReplyRunTokenService service) {
        @SuppressWarnings("unchecked")
        ObjectProvider<ReplyRunTokenService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(service);
        return new ReplyRunTokenAuthFilter(provider, organizations);
    }

    private static MockHttpServletRequest request(String path, String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRequestURI(path);
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }

    @Test
    @DisplayName("only the hash is stored; the token opens its run, and stops when the run is finished")
    void aTokenOpensItsRunUntilItIsFinished() {
        ReplyRunTokenService service = new ReplyRunTokenService(runs);
        UUID org = org();
        ReplyRunTokenService.Minted minted = service.mint(org, "REVIEW", UUID.randomUUID(), "INGEST");

        assertThat(minted.token()).startsWith("rvr_");
        ReplyAgentRun stored = runs.findById(minted.runId()).orElseThrow();
        assertThat(stored.getTokenHash()).isNotEqualTo(minted.token()).doesNotContain(minted.token());
        assertThat(service.authenticate(minted.token())).get().extracting(ReplyAgentRun::getId).isEqualTo(minted.runId());

        service.finish(minted.runId(), "SAVED");
        assertThat(service.authenticate(minted.token())).isEmpty();
        service.finish(minted.runId(), "FAILED");
        assertThat(runs.findById(minted.runId()).orElseThrow().getOutcome()).isEqualTo("SAVED");
    }

    @Test
    @DisplayName("a token past its lifetime opens nothing")
    void anExpiredTokenOpensNothing() {
        UUID org = org();
        Instant minted = Instant.parse("2026-10-28T03:00:00Z");
        ReplyRunTokenService.Minted run = new ReplyRunTokenService(runs, Clock.fixed(minted, ZoneOffset.UTC))
                .mint(org, "INQUIRY", UUID.randomUUID(), "SCHEDULED");
        ReplyRunTokenService later = new ReplyRunTokenService(runs,
                Clock.fixed(minted.plus(ReplyRunTokenService.LIFETIME).plusSeconds(1), ZoneOffset.UTC));
        assertThat(later.authenticate(run.token())).isEmpty();
    }

    @Test
    @DisplayName("on a reply-run route the run is the principal — no user — and its id rides on the request")
    void theFilterAdmitsTheRunRouteOnly() throws Exception {
        ReplyRunTokenService service = new ReplyRunTokenService(runs);
        UUID org = org();
        ReplyRunTokenService.Minted minted = service.mint(org, "REVIEW", UUID.randomUUID(), "INGEST");

        MockHttpServletRequest allowed = request("/api/agent/reply/run/classify", minted.token());
        MockHttpServletResponse ok = new MockHttpServletResponse();
        filter(service).doFilter(allowed, ok, new MockFilterChain());
        assertThat(ok.getStatus()).isEqualTo(200);
        AuthPrincipal principal = (AuthPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        assertThat(principal.orgId()).isEqualTo(org);
        assertThat(principal.userId()).isNull();
        assertThat(allowed.getAttribute(ReplyRunTokenAuthFilter.RUN_ID_ATTRIBUTE)).isEqualTo(minted.runId());
    }

    @Test
    @DisplayName("anywhere else a run token ends the request 401 — it never falls through to the seller's login")
    void theFilterRefusesEveryOtherRoute() throws Exception {
        ReplyRunTokenService service = new ReplyRunTokenService(runs);
        ReplyRunTokenService.Minted minted = service.mint(org(), "REVIEW", UUID.randomUUID(), "INGEST");
        for (String path : new String[] {"/api/seller-accounts/x/attention/items/review:y/reply/approval",
                "/api/answer-style", "/api/agent/reply/start", "/api/agent/quota"}) {
            MockHttpServletResponse refused = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();
            filter(service).doFilter(request(path, minted.token()), refused, chain);
            assertThat(refused.getStatus()).as(path).isEqualTo(401);
            assertThat(chain.getRequest()).as(path + " must not reach the next filter").isNull();
        }
        // A finished run's token is refused on its own route too.
        service.finish(minted.runId(), "SAVED");
        MockHttpServletResponse after = new MockHttpServletResponse();
        filter(service).doFilter(request("/api/agent/reply/run/classify", minted.token()), after, new MockFilterChain());
        assertThat(after.getStatus()).isEqualTo(401);
    }
}
