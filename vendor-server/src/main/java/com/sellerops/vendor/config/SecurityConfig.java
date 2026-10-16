package com.sellerops.vendor.config;

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

/**
 * <b>Closed by default.</b> {@code GET /health} is the only open route; every other request needs an
 * authenticated principal, and in this skeleton no authentication mechanism exists yet — so every other request is
 * 401. Seller sessions, install tokens and delegate TOTP (contract §2) each arrive as their own filter; until one
 * does, the route it would open stays shut.
 *
 * <p>Stateless and bearer-only by design (no cookies), hence no CSRF token; no form login, no HTTP Basic, and an
 * empty user store so Spring Boot does not generate a default password.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.GET, "/health").permitAll()
                        .anyRequest().authenticated());
        return http.build();
    }

    /** No users: there is no password login on this server, and Boot must not invent one. */
    @Bean
    UserDetailsService noUsers() {
        return new InMemoryUserDetailsManager();
    }
}
