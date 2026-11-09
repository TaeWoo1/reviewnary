package com.sellerops.connector.cafe24;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Every case of {@link Cafe24AnswerRecheckTest} again, on real Flyway V1…V186 over PostgreSQL — foreign keys, the V184,
 * V185 and V186 constraints, real timestamp precision and PostgreSQL's own row locking for the cases on real threads included.
 *
 * <p><b>Opt-in only</b> ({@code SELLEROPS_PG_PROOF=1}); point {@code SELLEROPS_PG_URL} at a disposable database — never
 * the development database or a kept QA database. Each test runs in a rolled-back transaction, so the database holds
 * nothing afterwards but the schema; the one case that commits on real threads deletes its rows itself and proves it.
 * The only rows written by hand are the parents the foreign keys demand.
 */
@EnabledIfEnvironmentVariable(named = "SELLEROPS_PG_PROOF", matches = "1")
class Cafe24AnswerRecheckPostgresProofIT extends Cafe24AnswerRecheckTest {

    @DynamicPropertySource
    static void disposablePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv().getOrDefault("SELLEROPS_PG_URL",
                "jdbc:postgresql://localhost:55432/sellerops_proof"));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.datasource.username", () -> "sellerops");
        registry.add("spring.datasource.password", () -> "sellerops_local_pw");
        registry.add("spring.flyway.enabled", () -> "true");
        // As in Cafe24CommentCheckPostgresProofIT: whole-schema validation stops on an older, unrelated type difference.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.sql.init.mode", () -> "never");
        registry.add("spring.jpa.defer-datasource-initialization", () -> "false");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;

    /**
     * What H2's entity-built schema cannot show (V185): an inquiry written by a writer that knows nothing of the new
     * columns starts at zero failures and no failure time; the constraints refuse a count without its time, a time without
     * a count, a negative count and a negative day's total; a day's row must belong to a real seller account. On its own
     * connection, rolled back.
     */
    @Test
    void v185StartsEveryInquiryAtZeroAndRefusesRecordsThatCannotBeTrue() throws SQLException {
        assertThat(jdbc.queryForObject("select success from flyway_schema_history where version = '185'",
                Boolean.class)).isTrue();
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                UUID o = UUID.randomUUID();
                UUID ch = UUID.randomUUID();
                UUID acc = UUID.randomUUID();
                st.executeUpdate("insert into organizations (id, name, created_at, updated_at) values ('" + o
                        + "', 'pg-proof-v185', now(), now())");
                st.executeUpdate("insert into channels (id, code, name_ko, status, created_at, updated_at) values ('" + ch
                        + "', 'PGPROOF_V185_" + ch.toString().substring(0, 8) + "', '증명용', 'AVAILABLE', now(), now())");
                st.executeUpdate("insert into seller_accounts (id, org_id, channel_id, connection_status, created_at, "
                        + "updated_at) values ('" + acc + "', '" + o + "', '" + ch + "', 'CONNECTED', now(), now())");
                UUID q = UUID.randomUUID();
                st.executeUpdate(inquiry(q, o, ch, null));
                var rs = st.executeQuery("select answer_recheck_failures, answer_recheck_failed_at from inquiries "
                        + "where id = '" + q + "'");
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isZero();
                assertThat(rs.getObject(2)).isNull();

                refused(st, inquiry(UUID.randomUUID(), o, ch, "1, null"), "ck_inquiries_answer_recheck_failed_pair");
                refused(st, inquiry(UUID.randomUUID(), o, ch, "0, now()"), "ck_inquiries_answer_recheck_failed_pair");
                refused(st, inquiry(UUID.randomUUID(), o, ch, "-1, now()"), "ck_inquiries_answer_recheck_failures");
                st.executeUpdate(inquiry(UUID.randomUUID(), o, ch, "2, now()"));

                refused(st, "insert into inquiry_recheck_daily_usage values ('" + acc + "', date '2026-11-08', -1, now())",
                        "ck_inquiry_recheck_daily_usage_nonneg");
                refused(st, "insert into inquiry_recheck_daily_usage values ('" + UUID.randomUUID()
                        + "', date '2026-11-08', 0, now())", "inquiry_recheck_daily_usage_seller_account_id_fkey");
                refused(st, "insert into inquiry_recheck_run_lock values ('" + UUID.randomUUID() + "', '"
                        + UUID.randomUUID() + "', now(), now())", "inquiry_recheck_run_lock_seller_account_id_fkey");
                st.executeUpdate("insert into inquiry_recheck_daily_usage values ('" + acc
                        + "', date '2026-11-08', 3, now())");
                st.executeUpdate("insert into inquiry_recheck_run_lock values ('" + acc + "', '" + UUID.randomUUID()
                        + "', now(), now() + interval '10 minutes')");
                st.executeUpdate("delete from seller_accounts where id = '" + acc + "'");
                var left = st.executeQuery("select (select count(*) from inquiry_recheck_daily_usage where "
                        + "seller_account_id = '" + acc + "') + (select count(*) from inquiry_recheck_run_lock where "
                        + "seller_account_id = '" + acc + "')");
                assertThat(left.next()).isTrue();
                assertThat(left.getInt(1)).as("an account's day and lock go with it").isZero();
            } finally {
                c.rollback();
            }
        }
    }

    /**
     * V186: an account's wait after account-side stops. The count starts at one, only the four account-side stop names are
     * accepted, the wait must end after the stop, a wait belongs to a real seller account and goes with it. On its own
     * connection, rolled back.
     */
    @Test
    void v186KeepsOnlyAWaitThatCanBeTrueAndDropsItWithTheAccount() throws SQLException {
        assertThat(jdbc.queryForObject("select success from flyway_schema_history where version = '186'",
                Boolean.class)).isTrue();
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                UUID o = UUID.randomUUID();
                UUID ch = UUID.randomUUID();
                UUID acc = UUID.randomUUID();
                st.executeUpdate("insert into organizations (id, name, created_at, updated_at) values ('" + o
                        + "', 'pg-proof-v186', now(), now())");
                st.executeUpdate("insert into channels (id, code, name_ko, status, created_at, updated_at) values ('" + ch
                        + "', 'PGPROOF_V186_" + ch.toString().substring(0, 8) + "', '증명용', 'AVAILABLE', now(), now())");
                st.executeUpdate("insert into seller_accounts (id, org_id, channel_id, connection_status, created_at, "
                        + "updated_at) values ('" + acc + "', '" + o + "', '" + ch + "', 'CONNECTED', now(), now())");

                refused(st, accountWait(acc, 0, "RATE_LIMITED", "interval '2 hours'"),
                        "ck_inquiry_recheck_account_wait_failures_pos");
                for (String notAccountSide : List.of("DONE", "LIMIT", "DAILY_LIMIT", "BUSY", "ACCOUNT_WAIT", "other")) {
                    refused(st, accountWait(acc, 1, notAccountSide, "interval '2 hours'"),
                            "ck_inquiry_recheck_account_wait_stop");
                }
                refused(st, accountWait(acc, 1, "CHANNEL_FAILED", "interval '0 seconds'"),
                        "ck_inquiry_recheck_account_wait_after");
                refused(st, accountWait(UUID.randomUUID(), 1, "CHANNEL_FAILED", "interval '2 hours'"),
                        "inquiry_recheck_account_wait_seller_account_id_fkey");
                for (String stop : List.of("RATE_LIMITED", "AUTH_FAILED", "AUTH_REJECTED", "CHANNEL_FAILED")) {
                    st.executeUpdate(accountWait(acc, 1, stop, "interval '2 hours'"));
                    st.executeUpdate("delete from inquiry_recheck_account_wait where seller_account_id = '" + acc + "'");
                }
                st.executeUpdate(accountWait(acc, 3, "AUTH_REJECTED", "interval '8 hours'"));
                st.executeUpdate("delete from seller_accounts where id = '" + acc + "'");
                var left = st.executeQuery("select count(*) from inquiry_recheck_account_wait where seller_account_id = '"
                        + acc + "'");
                assertThat(left.next()).isTrue();
                assertThat(left.getInt(1)).as("an account's wait goes with it").isZero();
            } finally {
                c.rollback();
            }
        }
    }

    private static String accountWait(UUID account, int failures, String stop, String waitFor) {
        return "insert into inquiry_recheck_account_wait (seller_account_id, failures, last_stop, failed_at, wait_until) "
                + "values ('" + account + "', " + failures + ", '" + stop + "', now(), now() + " + waitFor + ")";
    }

    private static String inquiry(UUID id, UUID o, UUID ch, String recheckValues) {
        return "insert into inquiries (id, org_id, channel_id, body, status, received_at, created_at, updated_at"
                + (recheckValues == null ? "" : ", answer_recheck_failures, answer_recheck_failed_at")
                + ") values ('" + id + "', '" + o + "', '" + ch + "', '합성 본문', 'UNANSWERED', now(), now(), now()"
                + (recheckValues == null ? "" : ", " + recheckValues) + ")";
    }

    private static void refused(Statement st, String sql, String constraint) throws SQLException {
        Connection c = st.getConnection();
        Savepoint sp = c.setSavepoint();
        assertThatThrownBy(() -> st.executeUpdate(sql)).isInstanceOf(SQLException.class)
                .hasMessageContaining(constraint);
        c.rollback(sp);
    }

    @Override
    protected void prepareTenants() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        for (UUID o : List.of(org, otherOrg)) {
            jdbc.update("insert into organizations (id, name, created_at, updated_at) values (?, ?, now(), now())",
                    o, "pg-proof-" + suffix);
        }
        for (UUID c : List.of(channel, otherChannel)) {
            jdbc.update("insert into channels (id, code, name_ko, status, created_at, updated_at) "
                    + "values (?, ?, '증명용', 'AVAILABLE', now(), now())", c, "PGPROOF_" + c.toString().substring(0, 8));
        }
        jdbc.update("insert into seller_accounts (id, org_id, channel_id, connection_status, created_at, updated_at) "
                + "values (?, ?, ?, 'CONNECTED', now(), now())", account, org, channel);
        jdbc.update("insert into seller_accounts (id, org_id, channel_id, connection_status, created_at, updated_at) "
                + "values (?, ?, ?, 'CONNECTED', now(), now())", otherAccount, otherOrg, channel);
    }

    @Override
    protected void dropTenants() {
        for (UUID a : List.of(account, otherAccount)) {
            jdbc.update("delete from seller_accounts where id = ?", a);
        }
        for (UUID c : List.of(channel, otherChannel)) {
            jdbc.update("delete from channels where id = ?", c);
        }
        for (UUID o : List.of(org, otherOrg)) {
            jdbc.update("delete from organizations where id = ?", o);
        }
    }
}
