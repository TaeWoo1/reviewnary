package com.sellerops.vendor.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sellerops.vendor.account.SellerAccounts;
import com.sellerops.vendor.store.Stores;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * <b>The workspace server acts for an organisation only through the operator's mapping</b> —
 * {@code docs/standing/server_operation_boundary_v1.md} §5 (owner decision 2026-10-31). Through the real chain:
 * the service token alone opens only the list of linked organisations; with an organisation it opens exactly what a
 * PC of the linked account may do and none of the PC's own routes; an unlinked, malformed or revoked organisation, a
 * wrong token and a suspended account all end in 401; and a seller who works through the workspace server cannot sign
 * in here at all.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WorkspaceLinksSecurityTest {

    /** The test profile configures this token's SHA-256 ({@code application-test.yml}). */
    static final String SERVICE_TOKEN = "rvw_test-only-workspace-service-token";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private MockMvc mvc;
    @Autowired
    private WorkspaceLinks links;
    @Autowired
    private SellerAccounts accounts;
    @Autowired
    private Stores stores;

    private UUID org;
    private UUID otherOrg;
    private String email;
    private String otherEmail;
    private UUID store;
    private UUID otherStore;

    @BeforeEach
    void twoLinkedOrganisations() {
        org = UUID.randomUUID();
        otherOrg = UUID.randomUUID();
        email = "web-seller-" + UUID.randomUUID() + "@example.com";
        otherEmail = "web-seller-" + UUID.randomUUID() + "@example.com";
        links.link(email, org, true);
        links.link(otherEmail, otherOrg, true);
        store = stores.add(accounts.idOf(email).orElseThrow(), Stores.Channel.NAVER, "합성 스토어");
        otherStore = stores.add(accounts.idOf(otherEmail).orElseThrow(), Stores.Channel.NAVER, "다른 스토어");
    }

    @Test
    void theServiceTokenAloneListsTheLinkedOrganisationsAndNothingElse() throws Exception {
        JsonNode listed = body(mvc.perform(get("/v1/workspace/organisations").with(service(null)))
                .andExpect(status().isOk()));
        assertThat(listed.path("organisations").toString()).contains(org.toString(), otherOrg.toString());
        mvc.perform(get("/v1/stores").with(service(null))).andExpect(status().isForbidden());
        mvc.perform(get("/v1/workspace/organisations")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/workspace/organisations").with(bearer("rvw_not-the-configured-token")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anOrganisationSeesOnlyItsOwnAccountsStores() throws Exception {
        mvc.perform(get("/v1/stores").with(service(org))).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(store.toString()));
        mvc.perform(get("/v1/stores").with(service(otherOrg))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(otherStore.toString()));
        mvc.perform(get("/v1/stores/" + store + "/reviews?cursor=0").with(service(org)))
                .andExpect(status().isOk());
        mvc.perform(get("/v1/stores/" + otherStore + "/reviews?cursor=0").with(service(org)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/v1/stores/" + store + "/tasks/changes?cursor=0").with(service(otherOrg)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/v1/installs/me").with(service(org))).andExpect(status().isOk())
                .andExpect(jsonPath("$.accountEmail").value(email));
    }

    @Test
    void anUnlinkedMalformedOrRevokedOrganisationAndAWrongTokenAreAllRefusedAlike() throws Exception {
        mvc.perform(get("/v1/stores").with(service(UUID.randomUUID()))).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/stores").with(bearer(SERVICE_TOKEN)).header(WorkspaceLinks.ORG_HEADER, "not-a-uuid"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/stores").with(bearer("rvw_wrong")).header(WorkspaceLinks.ORG_HEADER, org.toString()))
                .andExpect(status().isUnauthorized());

        assertThat(links.unlink(org)).isTrue();
        mvc.perform(get("/v1/stores").with(service(org))).andExpect(status().isUnauthorized());
        assertThat(links.organisations()).doesNotContain(org).contains(otherOrg);

        accounts.suspend(otherEmail);
        mvc.perform(get("/v1/stores").with(service(otherOrg))).andExpect(status().isUnauthorized());
        assertThat(links.organisations()).doesNotContain(otherOrg);
        accounts.reinstate(otherEmail);
        mvc.perform(get("/v1/stores").with(service(otherOrg))).andExpect(status().isOk());
    }

    /** What only a PC may do, and what only a seller's session may do, stay closed to the workspace server. */
    @Test
    void theWorkspaceServerHasNoneOfThePcsOrTheSellersOwnRoutes() throws Exception {
        mvc.perform(get("/v1/ip").with(service(org))).andExpect(status().isForbidden());
        mvc.perform(delete("/v1/installs/me").with(service(org))).andExpect(status().isForbidden());
        mvc.perform(get("/v1/installs").with(service(org))).andExpect(status().isForbidden());
        mvc.perform(post("/v1/installs/approve").with(service(org)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"userCode\":\"BCDFGHJK\"}")).andExpect(status().isForbidden());
        mvc.perform(get("/v1/delegate/tasks").with(service(org))).andExpect(status().isForbidden());
        mvc.perform(get("/v1/stores").with(service(org))).andExpect(status().isOk());
    }

    /** A web seller has one account — Reviewnary's. The account made for the mapping cannot sign in here. */
    @Test
    void aWorkspaceSellerCannotSignInHere() throws Exception {
        // A blank password is refused before any account is looked up.
        mvc.perform(post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"correct horse battery\"}"))
                .andExpect(status().isUnauthorized());
        assertThat(accounts.login(email, null)).isEmpty();
    }

    @Test
    void oneOrganisationIsLinkedToOneAccountBothWays() {
        assertThat(links.link(email, org, false).orgId()).as("the same pair again is not an error").isEqualTo(org);
        assertThatThrownBy(() -> links.link(otherEmail, org, false))
                .isInstanceOfSatisfying(WorkspaceLinks.LinkException.class,
                        e -> assertThat(e.failure()).isEqualTo(WorkspaceLinks.LinkFailure.ORGANISATION_LINKED_ELSEWHERE));
        assertThatThrownBy(() -> links.link(email, UUID.randomUUID(), false))
                .isInstanceOfSatisfying(WorkspaceLinks.LinkException.class,
                        e -> assertThat(e.failure()).isEqualTo(WorkspaceLinks.LinkFailure.ACCOUNT_LINKED_ELSEWHERE));
        assertThatThrownBy(() -> links.link("nobody-" + UUID.randomUUID() + "@example.com", UUID.randomUUID(), false))
                .isInstanceOfSatisfying(WorkspaceLinks.LinkException.class,
                        e -> assertThat(e.failure()).isEqualTo(WorkspaceLinks.LinkFailure.NO_SUCH_ACCOUNT));

        links.unlink(org);
        UUID again = UUID.randomUUID();
        assertThat(links.link(email, again, false).orgId()).as("unlinked: the account may be linked anew")
                .isEqualTo(again);
    }

    private static RequestPostProcessor service(UUID orgId) {
        return r -> {
            r.addHeader("Authorization", "Bearer " + SERVICE_TOKEN);
            if (orgId != null) {
                r.addHeader(WorkspaceLinks.ORG_HEADER, orgId.toString());
            }
            return r;
        };
    }

    private static RequestPostProcessor bearer(String token) {
        return r -> {
            r.addHeader("Authorization", "Bearer " + token);
            return r;
        };
    }

    private static JsonNode body(org.springframework.test.web.servlet.ResultActions actions) throws Exception {
        return JSON.readTree(actions.andReturn().getResponse().getContentAsString());
    }
}
