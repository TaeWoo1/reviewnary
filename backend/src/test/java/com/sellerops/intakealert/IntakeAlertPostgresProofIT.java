package com.sellerops.intakealert;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Every {@link IntakeAlertEngineTest} case again, on a disposable PostgreSQL with <b>real Flyway</b> — so the unique key,
 * the conditional updates and the four concurrent workers run against the production schema (V189), not H2.
 *
 * <p>Opt-in: {@code SELLEROPS_PG_PROOF=1} and an explicit {@code SELLEROPS_PG_URL} (no default — it must never land on
 * the demo database). Only JPA + Flyway start (no schedulers, no connectors, no senders).
 */
@EnabledIfEnvironmentVariable(named = "SELLEROPS_PG_PROOF", matches = "1")
class IntakeAlertPostgresProofIT extends IntakeAlertEngineTest {

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        String url = System.getenv("SELLEROPS_PG_URL");
        if (url == null || url.isBlank() || url.contains(":55432/")) {
            throw new IllegalStateException("SELLEROPS_PG_URL must name a disposable database (never the demo on 55432)");
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("SELLEROPS_PG_USER", "sellerops"));
        registry.add("spring.datasource.password",
                () -> System.getenv().getOrDefault("SELLEROPS_PG_PASSWORD", "sellerops_local_pw"));
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.sql.init.mode", () -> "never");
        // The H2 test profile defers SQL init behind JPA; with Flyway that is a circular dependency.
        registry.add("spring.jpa.defer-datasource-initialization", () -> "false");
    }

    @Autowired JdbcTemplate jdbc;

    @Override
    protected UUID newUserId(UUID org) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("insert into users (id, org_id, email, password_hash, name, role, created_at, updated_at) "
                        + "values (?, ?, ?, null, '알림 수신자', 'OWNER', ?, ?)",
                id, org, "intake-" + id + "@proof.invalid", java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));
        return id;
    }

    @Test
    void flywayAppliedV189WithTheUniqueKeys() {
        Integer applied = jdbc.queryForObject(
                "select count(*) from flyway_schema_history where version = '189' and success", Integer.class);
        assertThat(applied).isEqualTo(1);
        Integer keys = jdbc.queryForObject("""
                select count(*) from pg_constraint
                where conname in ('uq_intake_alert_items_subject', 'uq_intake_alert_settings_user') and contype = 'u'
                """, Integer.class);
        assertThat(keys).isEqualTo(2);
    }
}
