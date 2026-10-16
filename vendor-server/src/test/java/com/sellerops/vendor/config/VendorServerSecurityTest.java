package com.sellerops.vendor.config;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * <b>Closed by default</b>: only {@code /health} answers without a principal. The authenticated half (a mock
 * principal standing in for the install token that does not exist yet) is what keeps a chain that refused
 * everything from passing.
 */
@SpringBootTest
@AutoConfigureMockMvc
class VendorServerSecurityTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void healthIsTheOnlyOpenRoute() throws Exception {
        mvc.perform(get("/health")).andExpect(status().isOk()).andExpect(content().string("ok"));
        mvc.perform(get("/v1/ip")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/anything")).andExpect(status().isUnauthorized());
        mvc.perform(post("/health")).andExpect(status().isUnauthorized());
    }

    @Test
    void anAuthenticatedCallerSeesItsOwnIpv4() throws Exception {
        mvc.perform(get("/v1/ip").with(user("install")).with(r -> {
                    r.setRemoteAddr("203.0.113.7");
                    return r;
                }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ipv4").value("203.0.113.7"));
    }

    @Test
    void anIpv6CallerIsToldThereIsNoIpv4RatherThanGivenOne() throws Exception {
        mvc.perform(get("/v1/ip").with(user("install")).with(r -> {
                    r.setRemoteAddr("2001:db8::1");
                    return r;
                }))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("ipv4_unavailable"));
    }
}
