package com.sellerops.vendor.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerops.vendor.MutableClock;
import com.sellerops.vendor.TestTotp;
import com.sellerops.vendor.account.SellerAccounts;
import com.sellerops.vendor.delegate.Delegates;
import com.sellerops.vendor.delegate.TotpSecretBox;
import com.sellerops.vendor.store.Stores.Check;
import com.sellerops.vendor.store.Stores.OwnerBasis;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/**
 * <b>No seller's reviews reach the server before an admin has checked the store and the delegate</b> — contract §2,
 * V9. An assignment opens unchecked and does nothing until both checks are recorded: the store is the seller account
 * holder's, and that delegate's own NAVER account is its sub-manager. Who, when and on what basis stay on the rows.
 */
@SpringBootTest
@ActiveProfiles("test")
class StoreChecksTest {

    private static final String PASSWORD = "a long enough password";

    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private TotpSecretBox box;
    @Autowired
    private SellerAccounts accounts;

    private MutableClock clock;
    private Delegates delegates;
    private Stores stores;
    private UUID store;
    private String sellerEmail;
    private UUID me;
    private UUID admin;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-10-22T01:00:00Z"));
        delegates = new Delegates(jdbc, box, clock);
        stores = new Stores(jdbc, clock);
        sellerEmail = "s-" + UUID.randomUUID() + "@example.com";
        store = stores.add(accounts.redeem(accounts.issueInvite(sellerEmail), PASSWORD), Stores.Channel.NAVER,
                "확인 스토어");
        me = delegate("d-" + UUID.randomUUID() + "@example.com", Delegates.Role.DELEGATE);
        admin = delegate("a-" + UUID.randomUUID() + "@example.com", Delegates.Role.ADMIN);
    }

    @Test
    void anAssignmentWorksOnlyOnceAnAdminHasRecordedBothChecks() {
        stores.assign(store, me);
        assertThat(stores.isAssigned(store, me)).as("assigned, nothing checked").isFalse();
        assertThat(stores.assignedTo(me)).isEmpty();

        assertThat(stores.checkOwner(store, me, OwnerBasis.SELLER_CENTER_SCREEN)).isEqualTo(Check.NOT_ADMIN);
        assertThat(stores.checkAssignment(store, me, me)).as("not even their own").isEqualTo(Check.NOT_ADMIN);

        assertThat(stores.checkAssignment(store, me, admin)).isEqualTo(Check.CHECKED);
        assertThat(stores.isAssigned(store, me)).as("the delegate checked, the owner not yet").isFalse();

        clock.advance(java.time.Duration.ofMinutes(5));
        assertThat(stores.checkOwner(store, admin, OwnerBasis.SUBMANAGER_INVITE)).isEqualTo(Check.CHECKED);
        assertThat(stores.isAssigned(store, me)).isTrue();
        assertThat(stores.assignedTo(me)).extracting(Stores.View::id).containsExactly(store);

        UUID second = delegate("a2-" + UUID.randomUUID() + "@example.com", Delegates.Role.ADMIN);
        assertThat(stores.checkOwner(store, second, OwnerBasis.SELLER_CENTER_SCREEN)).isEqualTo(Check.ALREADY_CHECKED);
        assertThat(stores.checkAssignment(store, me, second)).isEqualTo(Check.ALREADY_CHECKED);
        Map<String, Object> owner = jdbc.sql("select owner_checked_by, owner_checked_at, owner_check_basis from stores "
                + "where id = ?").param(store).query().singleRow();
        assertThat(owner.get("owner_checked_by")).as("the first check stays").isEqualTo(admin);
        assertThat(((java.time.OffsetDateTime) owner.get("owner_checked_at")).toInstant()).isEqualTo(clock.instant());
        assertThat(owner.get("owner_check_basis")).isEqualTo("SUBMANAGER_INVITE");
        assertThat(jdbc.sql("select checked_by from store_assignments where store_id = ? and delegate_id = ?")
                .params(store, me).query(UUID.class).single()).isEqualTo(admin);
    }

    @Test
    void aCheckedStoreOpensOnlyTheCheckedDelegate() {
        UUID other = delegate("o-" + UUID.randomUUID() + "@example.com", Delegates.Role.DELEGATE);
        stores.assign(store, me);
        stores.assign(store, other);
        stores.checkOwner(store, admin, OwnerBasis.SELLER_CENTER_SCREEN);
        stores.checkAssignment(store, me, admin);
        assertThat(stores.isAssigned(store, me)).isTrue();
        assertThat(stores.isAssigned(store, other)).as("same store, its assignment not checked").isFalse();
    }

    @Test
    void anAssignmentEndedAndGivenAgainIsCheckedAgain() {
        stores.assign(store, me);
        stores.checkOwner(store, admin, OwnerBasis.SELLER_CENTER_SCREEN);
        stores.checkAssignment(store, me, admin);
        stores.unassign(store, me);
        assertThat(stores.checkAssignment(store, me, admin)).as("nothing open to check").isEqualTo(Check.NOT_FOUND);
        stores.assign(store, me);
        assertThat(stores.isAssigned(store, me)).isFalse();
        assertThat(stores.checkAssignment(store, me, admin)).isEqualTo(Check.CHECKED);
        assertThat(stores.isAssigned(store, me)).isTrue();
        assertThat(stores.checkOwner(UUID.randomUUID(), admin, OwnerBasis.SELLER_CENTER_SCREEN))
                .isEqualTo(Check.NOT_FOUND);
    }

    @Test
    void aSuspendedAdminChecksNothing() {
        stores.assign(store, me);
        delegates.suspend(jdbc.sql("select email from delegates where id = ?").param(admin).query(String.class)
                .single());
        assertThat(stores.checkOwner(store, admin, OwnerBasis.SELLER_CENTER_SCREEN)).isEqualTo(Check.NOT_ADMIN);
        assertThat(stores.checkAssignment(store, me, admin)).isEqualTo(Check.NOT_ADMIN);
    }

    @Test
    void theChecklistSaysWhatIsLeftAndWhoCheckedWhat() {
        stores.assign(store, me);
        Stores.ChecklistEntry before = entry();
        assertThat(before.sellerEmail()).isEqualTo(sellerEmail);
        assertThat(before.ownerCheckedAt()).isNull();
        assertThat(before.assignments()).singleElement().satisfies(a -> assertThat(a.checkedAt()).isNull());
        assertThat(before.receives()).isFalse();

        stores.checkOwner(store, admin, OwnerBasis.SELLER_CENTER_SCREEN);
        assertThat(entry().receives()).as("owner checked, no delegate checked").isFalse();
        stores.checkAssignment(store, me, admin);
        Stores.ChecklistEntry after = entry();
        assertThat(after.receives()).isTrue();
        assertThat(after.ownerBasis()).isEqualTo(OwnerBasis.SELLER_CENTER_SCREEN);
        assertThat(after.ownerCheckedBy()).startsWith("a-");
        assertThat(after.assignments()).singleElement().satisfies(a -> {
            assertThat(a.delegateEmail()).startsWith("d-");
            assertThat(a.checkedBy()).startsWith("a-");
            assertThat(a.checkedAt()).isEqualTo(clock.instant());
        });
    }

    private Stores.ChecklistEntry entry() {
        return stores.checklist().stream().filter(e -> e.storeId().equals(store)).findFirst().orElseThrow();
    }

    private UUID delegate(String email, Delegates.Role role) {
        String invite = delegates.issueInvite(email, role);
        Delegates.Staged staged = delegates.stage(invite, PASSWORD);
        return delegates.confirm(invite, TestTotp.code(staged.secret(), clock.instant(), 0)).delegateId();
    }
}
