package com.sellerops.vendor.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sellerops.vendor.account.SellerAccounts;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/**
 * <b>One organisation ↔ one seller account, held by the database on both sides</b> —
 * {@code docs/standing/server_operation_boundary_v1.md} §5 (external review 2026-10-31). The checks in
 * {@link WorkspaceLinks#link} read before they write, so two links started together can both pass them; only the
 * unique columns (V13 organisation, V14 account) decide which one stands.
 */
@SpringBootTest
@ActiveProfiles("test")
class WorkspaceLinksUniquenessTest {

    @Autowired
    private WorkspaceLinks links;
    @Autowired
    private SellerAccounts accounts;
    @Autowired
    private JdbcClient jdbc;

    /** Past every check in the service: a second live link for the account is refused by the table itself. */
    @Test
    void theTableRefusesASecondLiveLinkForOneAccount() {
        String email = "unique-" + UUID.randomUUID() + "@example.com";
        links.link(email, UUID.randomUUID(), true);
        UUID account = accounts.idOf(email).orElseThrow();
        assertThatThrownBy(() -> jdbc.sql("insert into installs (id, account_id, kind, workspace_org_id, "
                        + "workspace_account_id, device_name, created_at, expires_at) values (?, ?, 'WORKSPACE', ?, ?, "
                        + "'x', current_timestamp, current_timestamp)")
                .params(UUID.randomUUID(), account, UUID.randomUUID(), account).update())
                .isInstanceOf(DuplicateKeyException.class);
    }

    /** Eight links of one account to eight organisations at once: exactly one stands, the rest are told why. */
    @Test
    void linkingOneAccountToManyOrganisationsAtOnceLeavesExactlyOne() throws Exception {
        String email = "race-" + UUID.randomUUID() + "@example.com";
        UUID account = accounts.createWithoutPassword(email);
        int n = 8;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            UUID org = UUID.randomUUID();
            results.add(pool.submit(() -> {
                go.await();
                try {
                    links.link(email, org, false);
                    return "LINKED";
                } catch (WorkspaceLinks.LinkException e) {
                    return e.failure().name();
                }
            }));
        }
        go.countDown();
        List<String> outcomes = new ArrayList<>();
        for (Future<String> r : results) {
            outcomes.add(r.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertThat(outcomes).filteredOn("LINKED"::equals).hasSize(1);
        assertThat(outcomes).filteredOn(o -> !o.equals("LINKED")).containsOnly("ACCOUNT_LINKED_ELSEWHERE");
        assertThat(jdbc.sql("select count(*) from installs where kind = 'WORKSPACE' and account_id = ? "
                        + "and revoked_at is null").param(account).query(Integer.class).single()).isEqualTo(1);
    }

    /** Eight accounts to one organisation at once: exactly one stands. */
    @Test
    void linkingManyAccountsToOneOrganisationAtOnceLeavesExactlyOne() throws Exception {
        UUID org = UUID.randomUUID();
        int n = 8;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String email = "many-" + UUID.randomUUID() + "@example.com";
            accounts.createWithoutPassword(email);
            results.add(pool.submit(() -> {
                go.await();
                try {
                    links.link(email, org, false);
                    return "LINKED";
                } catch (WorkspaceLinks.LinkException e) {
                    return e.failure().name();
                }
            }));
        }
        go.countDown();
        List<String> outcomes = new ArrayList<>();
        for (Future<String> r : results) {
            outcomes.add(r.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertThat(outcomes).filteredOn("LINKED"::equals).hasSize(1);
        assertThat(outcomes).filteredOn(o -> !o.equals("LINKED")).containsOnly("ORGANISATION_LINKED_ELSEWHERE");
        assertThat(links.organisations()).filteredOn(org::equals).hasSize(1);
    }

    @Test
    void anUnlinkedAccountMayBeLinkedAgain() {
        String email = "again-" + UUID.randomUUID() + "@example.com";
        UUID first = UUID.randomUUID();
        links.link(email, first, true);
        links.unlink(first);
        UUID second = UUID.randomUUID();
        assertThat(links.link(email, second, false).orgId()).isEqualTo(second);
    }
}
