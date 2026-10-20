package com.sellerops.vendorlink;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * {@link VendorReviewPullScenarios} on a disposable PostgreSQL migrated by Flyway — the H2 test schema comes from
 * the entities and carries none of V136's foreign keys, so only this run shows the relay's rows fit the real
 * {@code reviews} · {@code organizations} · {@code channels} tables. Opt-in ({@code SELLEROPS_PG_PROOF=1}) against
 * {@code SELLEROPS_PG_URL}; point it at a throwaway database, never a real one.
 */
@EnabledIfEnvironmentVariable(named = "SELLEROPS_PG_PROOF", matches = "1")
class VendorReviewPullPostgresProofIT extends VendorReviewPullScenarios {

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv().getOrDefault("SELLEROPS_PG_URL",
                "jdbc:postgresql://localhost:55432/sellerops"));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.datasource.username", () -> "sellerops");
        registry.add("spring.datasource.password", () -> "sellerops_local_pw");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        // The H2 profile builds its schema from entities and then runs schema.sql; here Flyway owns the schema.
        registry.add("spring.jpa.defer-datasource-initialization", () -> "false");
        registry.add("spring.sql.init.mode", () -> "never");
        registry.add("sellerops.seed.enabled", () -> "false");
        registry.add("sellerops.seed.channel-catalogue", () -> "false");
    }
}
