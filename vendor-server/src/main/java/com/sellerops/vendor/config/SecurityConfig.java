package com.sellerops.vendor.config;

import com.sellerops.vendor.account.SellerAccounts;
import com.sellerops.vendor.auth.BearerAuthFilter;
import com.sellerops.vendor.auth.BearerAuthFilter.DelegatePrincipal;
import com.sellerops.vendor.auth.BearerAuthFilter.Principal;
import com.sellerops.vendor.auth.SessionRevocations;
import com.sellerops.vendor.auth.SessionTokens;
import com.sellerops.vendor.delegate.Delegates;
import com.sellerops.vendor.install.Installs;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

/**
 * <b>Closed by default; every open route is named here.</b> Contract §2.
 *
 * <ul>
 *   <li>No credential: {@code GET /health}, the pages ({@code /link}, {@code /replies}, {@code /delegate} and their
 *       {@code /assets}), the seller's invite redemption and
 *       login, the delegate's invite staging, confirmation and sign-in, and the PC's grant {@code code} /
 *       {@code token} calls.</li>
 *   <li>Install token only ({@link Principal#INSTALL}): {@code GET /v1/ip}, the PC's own row.</li>
 *   <li>Seller session only ({@link Principal#SELLER}): looking up and approving a PC, listing and revoking the
 *       account's PCs.</li>
 *   <li>Seller session or install token: the account's stores, and marking a review as needing no reply.</li>
 *   <li>Install token only: pulling a store's review events and confirming what was saved; submitting reply
 *       drafts, syncing their changes, reporting policy and a knowledge-based recheck.</li>
 *   <li>Seller session or install token: reading, editing, approving and cancelling reply tasks.</li>
 *   <li>Delegate session only ({@link DelegatePrincipal#DELEGATE}): everything under {@code /v1/delegate/} that is
 *       not sign-in.</li>
 *   <li>Everything else: refused — 401 without a credential, 403 with one.</li>
 * </ul>
 *
 * <p>Not built when the process runs an operator command ({@code OpsCommands}) — there is no web server then.
 *
 * <p>Stateless and bearer-only by design (no cookies), hence no CSRF token; no form login, no HTTP Basic, and an
 * empty user store so Spring Boot does not generate a default password.
 */
@Configuration
@ConditionalOnWebApplication
public class SecurityConfig {

    private static final String INSTALL = Principal.INSTALL.substring("ROLE_".length());
    private static final String SELLER = Principal.SELLER.substring("ROLE_".length());
    private static final String DELEGATE = DelegatePrincipal.DELEGATE.substring("ROLE_".length());

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, Installs installs, SessionTokens sessions,
                                    SellerAccounts accounts, Delegates delegates, SessionRevocations revocations)
            throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // The approval page (/link) is where a seller allows a PC onto their account: it must never be
                // framed (clickjacking the 「연결 허용」 press), run a script from anywhere but here, or hand the
                // code in its URL to another site as a referrer.
                .headers(h -> h
                        .frameOptions(f -> f.deny())
                        // A review's one attachment is shown from where NAVER keeps it (ReviewAttachment.HOST), and
                        // only from there.
                        .contentSecurityPolicy(c -> c.policyDirectives("default-src 'self'; "
                                + "img-src 'self' https://phinf.pstatic.net; frame-ancestors 'none'; "
                                + "base-uri 'none'; form-action 'none'; object-src 'none'"))
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterBefore(new BearerAuthFilter(installs, sessions, accounts, delegates, revocations),
                        UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(a -> a
                        // Spring's error page renders a status and nothing else (application.yml server.error).
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/health").permitAll()
                        .requestMatchers(HttpMethod.POST, "/v1/auth/login", "/v1/auth/invites/peek",
                                "/v1/auth/invites/redeem").permitAll()
                        .requestMatchers(HttpMethod.POST, "/v1/auth/logout").hasRole(SELLER)
                        .requestMatchers(HttpMethod.POST, "/v1/installs/code", "/v1/installs/token").permitAll()
                        .requestMatchers(HttpMethod.GET, "/v1/ip").hasRole(INSTALL)
                        .requestMatchers(HttpMethod.GET, "/v1/installs/me").hasRole(INSTALL)
                        .requestMatchers(HttpMethod.DELETE, "/v1/installs/me").hasRole(INSTALL)
                        .requestMatchers(HttpMethod.GET, "/link", "/link/*", "/replies", "/replies/*", "/delegate",
                                "/delegate/*", "/join", "/join/*", "/assets/*").permitAll()
                        // The same pages typed with a slash on the end: "/link/*" does not match "/link/".
                        .requestMatchers(HttpMethod.GET, "/link/", "/replies/", "/delegate/", "/delegate/join/",
                                "/join/").permitAll()
                        .requestMatchers(HttpMethod.POST, "/v1/installs/pending", "/v1/installs/approve",
                                "/v1/installs/step-up", "/v1/installs/step-up/verify").hasRole(SELLER)
                        .requestMatchers(HttpMethod.GET, "/v1/installs").hasRole(SELLER)
                        .requestMatchers(HttpMethod.DELETE, "/v1/installs/*").hasRole(SELLER)
                        .requestMatchers(HttpMethod.GET, "/v1/stores").hasAnyRole(SELLER, INSTALL)
                        .requestMatchers(HttpMethod.POST, "/v1/delegate/auth/invites/stage",
                                "/v1/delegate/auth/invites/confirm", "/v1/delegate/auth/login").permitAll()
                        .requestMatchers(HttpMethod.POST, "/v1/delegate/auth/logout").hasRole(DELEGATE)
                        .requestMatchers(HttpMethod.GET, "/v1/delegate/me").hasRole(DELEGATE)
                        .requestMatchers(HttpMethod.POST, "/v1/delegate/stores/*/review-uploads").hasRole(DELEGATE)
                        .requestMatchers(HttpMethod.GET, "/v1/stores/*/reviews").hasRole(INSTALL)
                        .requestMatchers(HttpMethod.POST, "/v1/stores/*/reviews/ack").hasRole(INSTALL)
                        .requestMatchers(HttpMethod.POST, "/v1/stores/*/reviews/*/disposition")
                        .hasAnyRole(SELLER, INSTALL)
                        // Reply tasks (contract §4.3). The PC's own calls first, then what the seller or the PC may do.
                        .requestMatchers(HttpMethod.POST, "/v1/stores/*/tasks", "/v1/stores/*/tasks/policy-check",
                                "/v1/tasks/*/pc-recheck", "/v1/tasks/*/approved-check").hasRole(INSTALL)
                        .requestMatchers(HttpMethod.GET, "/v1/stores/*/tasks/changes").hasRole(INSTALL)
                        .requestMatchers(HttpMethod.GET, "/v1/stores/*/tasks", "/v1/tasks/*").hasAnyRole(SELLER, INSTALL)
                        .requestMatchers(HttpMethod.PUT, "/v1/tasks/*/draft").hasAnyRole(SELLER, INSTALL)
                        .requestMatchers(HttpMethod.POST, "/v1/tasks/*/approve", "/v1/tasks/*/cancel")
                        .hasAnyRole(SELLER, INSTALL)
                        .requestMatchers(HttpMethod.GET, "/v1/delegate/tasks", "/v1/delegate/tasks/*/preflight")
                        .hasRole(DELEGATE)
                        .requestMatchers(HttpMethod.POST, "/v1/delegate/tasks/*/claim", "/v1/delegate/tasks/*/report",
                                "/v1/delegate/tasks/*/release").hasRole(DELEGATE)
                        .anyRequest().denyAll());
        return http.build();
    }

    /** No users: there is no form or Basic login on this server, and Boot must not invent one. */
    @Bean
    UserDetailsService noUsers() {
        return new InMemoryUserDetailsManager();
    }
}
