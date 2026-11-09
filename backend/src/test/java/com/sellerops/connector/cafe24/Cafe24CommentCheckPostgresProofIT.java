package com.sellerops.connector.cafe24;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Every case of {@link Cafe24CommentCheckIngestTest} again, on real Flyway V1…V184 over PostgreSQL, plus what H2's
 * entity-built schema cannot show: the V184 columns and constraints themselves, and that a row which existed before
 * V184 keeps all three columns null.
 *
 * <p><b>Opt-in only</b> ({@code SELLEROPS_PG_PROOF=1}); point {@code SELLEROPS_PG_URL} at a disposable database —
 * never the development database or a kept QA database. Every test runs inside a rolled-back transaction or on its own
 * connection that is rolled back, so the database holds nothing afterwards but the schema.
 */
@EnabledIfEnvironmentVariable(named = "SELLEROPS_PG_PROOF", matches = "1")
class Cafe24CommentCheckPostgresProofIT extends Cafe24CommentCheckIngestTest {

    @DynamicPropertySource
    static void disposablePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv().getOrDefault("SELLEROPS_PG_URL",
                "jdbc:postgresql://localhost:55432/sellerops_proof"));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.datasource.username", () -> "sellerops");
        registry.add("spring.datasource.password", () -> "sellerops_local_pw");
        registry.add("spring.flyway.enabled", () -> "true");
        // Not "validate": whole-schema validation already stops on an older, unrelated type difference
        // (knowledge_embedding.content_sha256 is char(64), the entity says varchar(64)). The three V184 columns are
        // checked by name and type below instead, and every ingest case reads and writes them through the entity.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        // schema.sql is H2's stand-in for objects Flyway owns in production.
        registry.add("spring.sql.init.mode", () -> "never");
        // The test profile defers datasource init for schema.sql; with Flyway on that is a circular depends-on.
        registry.add("spring.jpa.defer-datasource-initialization", () -> "false");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;

    // The constraint checks run on a second connection. Reusing the test transaction's own org/channel ids there would
    // wait on that transaction's uncommitted rows, so the second connection gets parents of its own.
    private final UUID ownOrg = UUID.randomUUID();
    private final UUID ownChannel = UUID.randomUUID();

    @Override
    protected void prepareTenants() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        for (UUID o : List.of(org, otherOrg)) {
            jdbc.update("insert into organizations (id, name, created_at, updated_at) values (?, ?, now(), now())",
                    o, "pg-proof-" + suffix);
        }
        insertChannel(channel, "PGPROOF_C24_" + suffix);
        insertChannel(naverChannel, "PGPROOF_NV_" + suffix);
        insertAccount(account, org);
        insertAccount(otherAccount, otherOrg);
    }

    private void insertChannel(UUID id, String code) {
        jdbc.update("insert into channels (id, code, name_ko, status, created_at, updated_at) "
                + "values (?, ?, '증명용', 'AVAILABLE', now(), now())", id, code);
    }

    private void insertAccount(UUID id, UUID orgId) {
        jdbc.update("insert into seller_accounts (id, org_id, channel_id, connection_status, created_at, updated_at) "
                + "values (?, ?, ?, 'CONNECTED', now(), now())", id, orgId, channel);
    }

    @Test
    void v184IsAppliedAndItsColumnsAreNullableWithNoDefault() {
        assertThat(jdbc.queryForObject("select success from flyway_schema_history where version = '184'",
                Boolean.class)).isTrue();
        List<Map<String, Object>> cols = jdbc.queryForList("select column_name, data_type, is_nullable, column_default "
                + "from information_schema.columns where table_name = 'inquiries' and column_name like 'comment_check%' "
                + "order by column_name");
        assertThat(cols).extracting(c -> c.get("column_name"))
                .containsExactly("comment_check", "comment_check_attempted_at", "comment_checked_at");
        assertThat(cols).allSatisfy(c -> {
            assertThat(c.get("is_nullable")).isEqualTo("YES");
            assertThat(c.get("column_default")).isNull();
        });
        assertThat(cols).extracting(c -> c.get("data_type"))
                .containsExactly("character varying", "timestamp with time zone", "timestamp with time zone");
    }

    @Test
    void aRowWrittenWithoutTheNewColumnsKeepsThemNull() throws SQLException {
        // The shape of every inquiry that existed before V184: nothing names the new columns.
        withOwnConnection(st -> {
            UUID id = UUID.randomUUID();
            st.executeUpdate(insertInquirySql(id, null));
            var rs = st.executeQuery("select comment_check, comment_check_attempted_at, comment_checked_at "
                    + "from inquiries where id = '" + id + "'");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isNull();
            assertThat(rs.getObject(2)).isNull();
            assertThat(rs.getObject(3)).isNull();
        });
    }

    @Test
    void theConstraintsRefuseRecordsThatCannotBeTrue() throws SQLException {
        withOwnConnection(st -> {
            // A value outside the three.
            refused(st, "'MAYBE', now(), null");
            // A result with no time it was produced, and a time with no result.
            refused(st, "'NO_SHOP_COMMENT', null, null");
            refused(st, "null, now(), null");
            // A success time with no result at all.
            refused(st, "null, null, now()");
            // And the shapes that ARE true are accepted: a failed look with no success yet, a failed look after one.
            st.executeUpdate(insertInquirySql(UUID.randomUUID(), "'NOT_CHECKED', now(), null"));
            st.executeUpdate(insertInquirySql(UUID.randomUUID(), "'NOT_CHECKED', now(), now() - interval '1 day'"));
            st.executeUpdate(insertInquirySql(UUID.randomUUID(), "'SHOP_ANSWERED', now(), now()"));
        });
    }

    private void refused(Statement st, String commentValues) throws SQLException {
        Connection c = st.getConnection();
        Savepoint sp = c.setSavepoint();
        assertThatThrownBy(() -> st.executeUpdate(insertInquirySql(UUID.randomUUID(), commentValues)))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ck_inquiries_comment_check");
        c.rollback(sp);
    }

    /** An inquiry insert naming only the columns a pre-V184 writer knew — plus, when given, the three new ones. */
    private String insertInquirySql(UUID id, String commentValues) {
        String base = "insert into inquiries (id, org_id, channel_id, body, status, received_at, created_at, updated_at"
                + (commentValues == null ? "" : ", comment_check, comment_check_attempted_at, comment_checked_at")
                + ") values ('" + id + "', '" + ownOrg + "', '" + ownChannel + "', '합성 본문', 'UNANSWERED', now(), now(), now()"
                + (commentValues == null ? "" : ", " + commentValues) + ")";
        return base;
    }

    @FunctionalInterface
    private interface SqlWork {
        void run(Statement st) throws SQLException;
    }

    /** Its own connection, its own parent rows, rolled back at the end — outside the test-managed transaction. */
    private void withOwnConnection(SqlWork work) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                String suffix = UUID.randomUUID().toString().substring(0, 8);
                st.executeUpdate("insert into organizations (id, name, created_at, updated_at) values ('" + ownOrg
                        + "', 'pg-proof-own', now(), now())");
                st.executeUpdate("insert into channels (id, code, name_ko, status, created_at, updated_at) values ('"
                        + ownChannel + "', 'PGPROOF_OWN_" + suffix + "', '증명용', 'AVAILABLE', now(), now())");
                work.run(st);
            } finally {
                c.rollback();
            }
        }
    }
}
