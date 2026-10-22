package com.sellerops.vendor.ops;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerops.vendor.TestTotp;
import com.sellerops.vendor.account.SellerAccounts;
import com.sellerops.vendor.auth.SessionRevocations;
import com.sellerops.vendor.auth.SessionTokens;
import com.sellerops.vendor.delegate.Delegates;
import com.sellerops.vendor.retention.Retention;
import com.sellerops.vendor.store.Stores;
import com.sellerops.vendor.task.ReplyTasks;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

@SpringBootTest
@ActiveProfiles("test")
class OpsCommandsTest {

    @Autowired
    private SellerAccounts accounts;
    @Autowired
    private Delegates delegates;
    @Autowired
    private Stores stores;
    @Autowired
    private SessionRevocations revocations;
    @Autowired
    private SessionTokens sessions;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private PlatformTransactionManager transactions;
    @Autowired
    private ReplyTasks tasks;
    /** At the epoch nothing has expired: the command's own pass, without touching other tests' rows. */
    private Retention retention;

    private OpsCommands.Services services;
    private ByteArrayOutputStream out;

    @BeforeEach
    void setUp() {
        retention = new Retention(jdbc, transactions, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        services = new OpsCommands.Services(accounts, delegates, stores, revocations, retention, tasks,
                "https://vendor.example.com/");
        out = new ByteArrayOutputStream();
    }

    @Test
    void theInviteLinkPrintedOnceRedeemsIntoAnAccount() {
        String email = "ops-" + UUID.randomUUID() + "@example.com";
        assertThat(run("invite", email)).isZero();
        String link = lastWord();
        assertThat(link).startsWith("https://vendor.example.com/join#");
        String code = link.substring(link.indexOf('#') + 1);
        assertThat(accounts.inviteEmail(code)).contains(email);
        assertThat(accounts.redeem(code, "a long enough password")).isNotNull();
        assertThat(accounts.inviteEmail(code)).as("a spent invite names nobody").isEmpty();
        assertThat(run("suspend", email)).isZero();
        assertThat(run("suspend", "nobody@example.com")).isEqualTo(1);
    }

    @Test
    void endSessionsSignsThePersonOutAsSellerAndDelegateAndLeavesTheAccounts() {
        String email = "ops-both-" + UUID.randomUUID() + "@example.com";
        UUID seller = accounts.redeem(accounts.issueInvite(email), "a long enough password");
        String invite = delegates.issueInvite(email, Delegates.Role.ADMIN);
        Delegates.Staged staged = delegates.stage(invite, "a long enough password");
        UUID delegate = delegates.confirm(invite, TestTotp.code(staged.secret(), Instant.now(), 0)).delegateId();
        SessionTokens.Session asSeller = sessions.verifyAny(sessions.issue(seller).token()).orElseThrow();
        SessionTokens.Session asDelegate = sessions.verifyAny(
                sessions.issue(SessionTokens.Kind.DELEGATE, delegate).token()).orElseThrow();
        assertThat(revocations.live(asSeller)).isTrue();

        assertThat(run("end-sessions", email.toUpperCase())).isZero();
        assertThat(out.toString()).contains("sessions ended: seller delegate");
        assertThat(revocations.live(asSeller)).isFalse();
        assertThat(revocations.live(asDelegate)).isFalse();
        assertThat(accounts.isActive(seller)).as("not a suspension").isTrue();
        assertThat(delegates.active(delegate)).isPresent();
        assertThat(run("end-sessions", "nobody@example.com")).isEqualTo(1);
    }

    @Test
    void aDelegateIsInvitedAStoreAddedAndTheTwoJoinedThenParted() {
        String seller = "ops-seller-" + UUID.randomUUID() + "@example.com";
        accounts.redeem(accounts.issueInvite(seller), "a long enough password");
        String person = "ops-delegate-" + UUID.randomUUID() + "@example.com";

        assertThat(run("invite-delegate", person, "admin")).isZero();
        String link = lastWord();
        assertThat(link).startsWith("https://vendor.example.com/delegate/join#");
        String invite = link.substring(link.indexOf('#') + 1);
        Delegates.Staged staged = delegates.stage(invite, "a long enough password");
        UUID delegateId = delegates.confirm(invite, TestTotp.code(staged.secret(), Instant.now(), 0)).delegateId();
        assertThat(delegates.active(delegateId)).hasValueSatisfying(
                d -> assertThat(d.role()).isEqualTo(Delegates.Role.ADMIN));

        assertThat(run("add-store", seller, "데모", "스마트스토어")).isZero();
        UUID storeId = UUID.fromString(lastWord());
        assertThat(stores.ofAccount(accounts.idOf(seller).orElseThrow()))
                .singleElement().satisfies(v -> assertThat(v.label()).isEqualTo("데모 스마트스토어"));

        assertThat(run("assign", person, storeId.toString())).isZero();
        assertThat(run("assign", person, storeId.toString())).isEqualTo(1);
        assertThat(stores.assignedTo(delegateId)).as("assigned, not yet checked").isEmpty();
        assertThat(run("checklist")).isZero();
        assertThat(output()).contains("store " + storeId + " · 데모 스마트스토어 · seller " + seller
                + " · does not receive reviews yet", "[ ] 판매자 소유", "[ ] 담당자 배정 " + person);
        assertThat(run("check-owner", storeId.toString(), "trust-me", person)).isEqualTo(2);
        assertThat(run("check-owner", storeId.toString(), "seller-center-screen", "nobody@example.com"))
                .isEqualTo(1);
        assertThat(run("check-owner", storeId.toString(), "seller-center-screen", person)).isZero();
        assertThat(run("check-assignment", person, storeId.toString(), person)).isZero();
        assertThat(run("check-assignment", person, storeId.toString(), person)).as("already checked").isZero();
        assertThat(lastLine()).isEqualTo("already checked");
        assertThat(stores.assignedTo(delegateId)).extracting(Stores.View::id).containsExactly(storeId);
        assertThat(run("checklist")).isZero();
        assertThat(output()).contains("receives reviews", "[x] 판매자 소유 — " + person, "seller-center-screen",
                "[x] 담당자 배정 " + person + " — " + person);
        assertThat(run("unassign", person, storeId.toString())).isZero();
        assertThat(stores.assignedTo(delegateId)).isEmpty();

        assertThat(run("suspend-delegate", person)).isZero();
        assertThat(run("assign", person, storeId.toString())).isEqualTo(1);
        assertThat(run("reinstate-delegate", person)).isZero();
        assertThat(run("assign", person, storeId.toString())).isZero();
    }

    @Test
    void withoutAPublicAddressTheJoinLinkIsPrintedAsAPath() {
        services = new OpsCommands.Services(accounts, delegates, stores, revocations, retention, tasks, "");
        assertThat(run("invite-delegate", "ops-path-" + UUID.randomUUID() + "@example.com")).isZero();
        String path = lastWord();
        assertThat(path).startsWith("/delegate/join#");
        Delegates.Staged staged = delegates.stage(path.substring(path.indexOf('#') + 1), "a long enough password");
        assertThat(staged.secret()).isNotBlank();
    }

    @Test
    void anUnknownPersonOrStoreIsNamedAndNothingChanges() {
        UUID store = UUID.randomUUID();
        assertThat(run("add-store", "nobody@example.com", "x")).isEqualTo(1);
        assertThat(run("assign", "nobody@example.com", store.toString())).isEqualTo(1);
        assertThat(run("assign", "nobody@example.com", "not-a-uuid")).isEqualTo(2);
        assertThat(run("suspend-delegate", "nobody@example.com")).isEqualTo(1);
    }

    @Test
    void anythingElseIsAUsageError() {
        assertThat(run("invite")).isEqualTo(2);
        assertThat(run("delete", "a@example.com")).isEqualTo(2);
        assertThat(run("invite", "not-an-address")).isEqualTo(2);
        assertThat(run("invite-delegate", "a@example.com", "owner")).isEqualTo(2);
        assertThat(run("invite-delegate", "not-an-address")).isEqualTo(2);
        assertThat(run("assign", "a@example.com")).isEqualTo(2);
        assertThat(run("suspend", "a@example.com", "extra")).isEqualTo(2);
        assertThat(run("purge", "now")).isEqualTo(2);
        assertThat(run("in-doubt", "now")).isEqualTo(2);
        assertThat(run("settle", "not-a-uuid", "posted", "a@example.com")).isEqualTo(2);
        assertThat(run("settle", UUID.randomUUID().toString(), "maybe", "a@example.com")).isEqualTo(2);
        assertThat(run("settle", UUID.randomUUID().toString(), "posted")).isEqualTo(2);
    }

    @Test
    void settlingNeedsAnActiveAdminAndAnInDoubtTask() {
        assertThat(run("in-doubt")).isZero();
        assertThat(run("settle", UUID.randomUUID().toString(), "posted", "nobody@example.com")).isEqualTo(1);
        String admin = "ops-admin-" + UUID.randomUUID() + "@example.com";
        String invite = delegates.issueInvite(admin, Delegates.Role.ADMIN);
        Delegates.Staged staged = delegates.stage(invite, "a long enough password");
        delegates.confirm(invite, TestTotp.code(staged.secret(), Instant.now(), 0));
        out.reset();
        assertThat(run("settle", UUID.randomUUID().toString(), "not-posted", admin)).isEqualTo(1);
        assertThat(out.toString(StandardCharsets.UTF_8).trim())
                .isEqualTo("no in-doubt task of a suspended delegate with that id");
    }

    @Test
    void purgeRunsOneRetentionPassAndPrintsCountsOnly() {
        assertThat(run("purge")).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8).trim())
                .isEqualTo("deleted: reviews 0 · tasks 0 · invites 0 · logged-out sessions 0");
    }

    private int run(String... args) {
        return OpsCommands.run(services, args, new PrintStream(out, true, StandardCharsets.UTF_8));
    }

    private String lastWord() {
        String printed = out.toString(StandardCharsets.UTF_8).trim();
        return printed.substring(printed.lastIndexOf(' ') + 1);
    }

    private String output() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String lastLine() {
        String printed = output().trim();
        return printed.substring(printed.lastIndexOf('\n') + 1);
    }
}
