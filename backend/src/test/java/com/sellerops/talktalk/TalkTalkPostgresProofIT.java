package com.sellerops.talktalk;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Every {@link TalkTalkLaneTest} case again on a disposable PostgreSQL with <b>real Flyway</b> — so V191's columns, checks, unique keys, the partial unique index and the row locks are the
 * production ones, not H2's entity-built schema.
 *
 * <p>Opt-in: {@code SELLEROPS_PG_PROOF=1} and an explicit {@code SELLEROPS_PG_URL} (no default — never a demo DB).
 */
@EnabledIfEnvironmentVariable(named = "SELLEROPS_PG_PROOF", matches = "1")
class TalkTalkPostgresProofIT extends TalkTalkLaneTest {

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        String url = System.getenv("SELLEROPS_PG_URL");
        if (url == null || url.isBlank() || url.contains(":55432/") || url.contains(":55436/")
                || url.contains(":55438/")) {
            throw new IllegalStateException("SELLEROPS_PG_URL must name a disposable database (never a demo database)");
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("SELLEROPS_PG_USER", "sellerops"));
        registry.add("spring.datasource.password",
                () -> System.getenv().getOrDefault("SELLEROPS_PG_PASSWORD", "sellerops_local_pw"));
        registry.add("spring.flyway.enabled", () -> "true");
        // Not "validate": an unrelated pre-existing mismatch (knowledge_embedding.content_sha256 char vs varchar) fails it.
        // Every TalkTalk column is written and read by the inherited cases instead.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.sql.init.mode", () -> "never");
        registry.add("spring.jpa.defer-datasource-initialization", () -> "false");
    }

    @Test
    void flywayAppliedV191WithItsKeys() {
        Integer applied = jdbc.queryForObject(
                "select count(*) from flyway_schema_history where version = '191' and success", Integer.class);
        assertThat(applied).isEqualTo(1);
        Integer keys = jdbc.queryForObject("""
                select count(*) from pg_constraint where contype = 'u' and conname in
                ('uq_talktalk_connections_org', 'uq_talktalk_connections_token', 'uq_talktalk_conversations_user',
                 'uq_talktalk_guidance_version', 'uq_talktalk_drafts_version', 'uq_talktalk_outbound_command')
                """, Integer.class);
        assertThat(keys).isEqualTo(6);
        Integer partial = jdbc.queryForObject(
                "select count(*) from pg_indexes where indexname = 'uq_talktalk_guidance_confirmed'", Integer.class);
        assertThat(partial).isEqualTo(1);
    }
}
