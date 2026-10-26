package com.sellerops.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sellerops.auth.AuthPrincipal;
import com.sellerops.auth.JwtTokenProvider;
import com.sellerops.config.SecurityConfig;
import com.sellerops.organization.OrganizationRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The open screen's question over the REAL {@link SecurityConfig}: the organisation is the token's, an answer is held
 * until that organisation's number moves, and the held answer reaches the browser on its second (ASYNC) dispatch.
 */
@WebMvcTest(ScreenChangesController.class)
@Import({SecurityConfig.class, ScreenChanges.class})
@ActiveProfiles("test")
class ScreenChangesControllerTest {

    private static final String TOKEN = "test-only-token-never-a-real-jwt";

    @Autowired MockMvc mockMvc;
    @Autowired ScreenChanges changes;
    @MockBean JwtTokenProvider tokenProvider;
    @MockBean OrganizationRepository organizations;

    private final UUID orgId = UUID.randomUUID();
    private final UUID otherOrg = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(tokenProvider.parse(TOKEN)).thenReturn(new AuthPrincipal(UUID.randomUUID(), orgId, "op@example.com"));
        when(organizations.existsById(orgId)).thenReturn(true);
    }

    @Test
    void aScreenWithoutASessionIsNotAnswered() throws Exception {
        mockMvc.perform(get("/api/live/changes")).andExpect(status().isUnauthorized());
    }

    @Test
    void aHeldQuestionIsAnsweredWhenItsOrganisationChangesAndNotBefore() throws Exception {
        long now = changes.version(orgId);

        MvcResult held = mockMvc.perform(get("/api/live/changes").param("after", Long.toString(now))
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(request().asyncStarted())
                .andReturn();
        assertThat(changes.waitingFor(orgId)).isEqualTo(1);

        changes.changed(otherOrg);
        assertThat(changes.waitingFor(orgId)).as("another organisation's change is not this screen's").isEqualTo(1);

        changes.changed(orgId);
        mockMvc.perform(asyncDispatch(held))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(now + 1));
        assertThat(changes.waitingFor(orgId)).as("an answered question is forgotten").isZero();
    }

    @Test
    void aScreenBehindTheNumberOrWithoutOneIsAnsweredAtOnce() throws Exception {
        changes.changed(orgId);
        long now = changes.version(orgId);

        for (String after : new String[] {Long.toString(now - 1), null}) {
            var call = get("/api/live/changes").header("Authorization", "Bearer " + TOKEN);
            if (after != null) {
                call = call.param("after", after);
            }
            MvcResult answered = mockMvc.perform(call).andExpect(request().asyncStarted()).andReturn();
            mockMvc.perform(asyncDispatch(answered))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.version").value(now));
        }
    }
}
