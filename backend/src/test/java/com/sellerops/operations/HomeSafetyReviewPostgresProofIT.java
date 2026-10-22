package com.sellerops.operations;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * {@link HomeSafetyReviewTest} on a disposable PostgreSQL migrated by Flyway — the safety read is a new JPQL query
 * (a 43-way {@code like} filter beside the tier rank), and only this run shows PostgreSQL reads it the way H2 does.
 * Opt-in ({@code SELLEROPS_PG_PROOF=1}) against {@code SELLEROPS_PG_URL}; point it at a throwaway database, never a
 * real one.
 */
@EnabledIfEnvironmentVariable(named = "SELLEROPS_PG_PROOF", matches = "1")
class HomeSafetyReviewPostgresProofIT extends HomeSafetyReviewTest {

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv().getOrDefault("SELLEROPS_PG_URL",
                "jdbc:postgresql://localhost:55432/sellerops"));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.datasource.username", () -> "sellerops");
        registry.add("spring.datasource.password", () -> "sellerops_local_pw");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.jpa.defer-datasource-initialization", () -> "false");
        registry.add("spring.sql.init.mode", () -> "never");
        registry.add("sellerops.seed.enabled", () -> "false");
        registry.add("sellerops.seed.channel-catalogue", () -> "false");
    }
}
