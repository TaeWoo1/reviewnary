package com.sellerops.vendor.delegate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sellerops.vendor.Codes;
import com.sellerops.vendor.MutableClock;
import com.sellerops.vendor.TestTotp;
import com.sellerops.vendor.account.SellerAccounts;
import com.sellerops.vendor.delegate.Delegates.EnrolException;
import com.sellerops.vendor.delegate.Delegates.EnrolFailure;
import com.sellerops.vendor.store.Stores;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/**
 * <b>A delegate signs in with a password and an authenticator code, and exists only after proving the code
 * works</b> — contract §2, D2 · D3.
 */
@SpringBootTest
@ActiveProfiles("test")
class DelegatesTest {

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
    private String email;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-10-20T00:00:10Z"));
        delegates = new Delegates(jdbc, box, clock);
        stores = new Stores(jdbc, clock);
        email = "d-" + UUID.randomUUID() + "@example.com";
    }

    @Test
    void choosingAPasswordDoesNotSpendTheInviteTheFirstCorrectCodeDoes() {
        String invite = delegates.issueInvite(email, Delegates.Role.DELEGATE);
        Delegates.Staged staged = delegates.stage(invite, PASSWORD);
        assertThat(staged.otpauthUri()).startsWith("otpauth://totp/reviewnary%3A")
                .contains("secret=" + staged.secret()).contains("issuer=reviewnary").contains("digits=6");
        assertThat(delegates.idOf(email)).isEmpty();

        assertThatThrownBy(() -> delegates.confirm(invite, wrong(staged.secret())))
                .isInstanceOfSatisfying(EnrolException.class,
                        e -> assertThat(e.failure()).isEqualTo(EnrolFailure.WRONG_CODE));
        assertThat(delegates.idOf(email)).as("a wrong code creates nothing and spends nothing").isEmpty();

        Delegates.Enrolled enrolled = delegates.confirm(invite, now(staged.secret()));
        assertThat(delegates.idOf(email)).hasValue(enrolled.delegateId());
        assertThat(enrolled.recoveryCodes()).hasSize(Delegates.RECOVERY_CODES).doesNotHaveDuplicates()
                .allMatch(c -> c.matches("[BCDFGHJKLMNPQRSTVWXZ2-9]{5}-[BCDFGHJKLMNPQRSTVWXZ2-9]{5}"));
        assertThatThrownBy(() -> delegates.confirm(invite, now(staged.secret())))
                .isInstanceOfSatisfying(EnrolException.class,
                        e -> assertThat(e.failure()).isEqualTo(EnrolFailure.INVALID_INVITE));
        assertThatThrownBy(() -> delegates.stage(invite, PASSWORD)).isInstanceOf(EnrolException.class);
    }

    @Test
    void stagingAgainReplacesTheSecretSoALostQrScreenCanBeRedone() {
        String invite = delegates.issueInvite(email, Delegates.Role.DELEGATE);
        Delegates.Staged first = delegates.stage(invite, PASSWORD);
        Delegates.Staged second = delegates.stage(invite, PASSWORD);
        assertThat(second.secret()).isNotEqualTo(first.secret());
        assertThatThrownBy(() -> delegates.confirm(invite, now(first.secret())))
                .isInstanceOfSatisfying(EnrolException.class,
                        e -> assertThat(e.failure()).isEqualTo(EnrolFailure.WRONG_CODE));
        assertThat(delegates.confirm(invite, now(second.secret())).delegateId()).isNotNull();
    }

    @Test
    void anInviteMustBeStagedLiveAndForAnAddressNotYetRegistered() {
        String invite = delegates.issueInvite(email, Delegates.Role.DELEGATE);
        assertThatThrownBy(() -> delegates.confirm(invite, "123456"))
                .isInstanceOfSatisfying(EnrolException.class,
                        e -> assertThat(e.failure()).isEqualTo(EnrolFailure.NOT_STAGED));
        assertThatThrownBy(() -> delegates.stage(invite, "short"))
                .isInstanceOfSatisfying(EnrolException.class,
                        e -> assertThat(e.failure()).isEqualTo(EnrolFailure.WEAK_PASSWORD));
        String late = delegates.issueInvite("late-" + email, Delegates.Role.DELEGATE);
        clock.advance(Delegates.INVITE_LIFETIME);
        assertThatThrownBy(() -> delegates.stage(late, PASSWORD))
                .isInstanceOfSatisfying(EnrolException.class,
                        e -> assertThat(e.failure()).isEqualTo(EnrolFailure.INVALID_INVITE));

        enrol(email);
        String again = delegates.issueInvite(email.toUpperCase(), Delegates.Role.DELEGATE);
        assertThatThrownBy(() -> delegates.stage(again, PASSWORD))
                .isInstanceOfSatisfying(EnrolException.class,
                        e -> assertThat(e.failure()).isEqualTo(EnrolFailure.ALREADY_REGISTERED));
    }

    @Test
    void signInNeedsThePasswordAndExactlyOneSecondFactorAndACodeStepIsUsedOnce() {
        Enrolment me = enrol(email);
        clock.advance(Duration.ofSeconds(Totp.STEP_SECONDS));
        String code = now(me.secret());
        assertThat(delegates.login(email, "wrong password!!", code, null)).isEmpty();
        assertThat(delegates.login(email, PASSWORD, null, null)).as("no second factor").isEmpty();
        assertThat(delegates.login(email, PASSWORD, code, me.recovery(0))).as("both factors").isEmpty();
        assertThat(delegates.login(email, PASSWORD, wrong(me.secret()), null)).isEmpty();
        assertThat(delegates.login(email.toUpperCase(), PASSWORD, code, null)).hasValue(me.id());
        assertThat(delegates.login(email, PASSWORD, code, null)).as("the same step again").isEmpty();
        clock.advance(Duration.ofSeconds(Totp.STEP_SECONDS));
        assertThat(delegates.login(email, PASSWORD, now(me.secret()), null)).hasValue(me.id());
        assertThat(delegates.login("nobody@example.com", PASSWORD, now(me.secret()), null)).isEmpty();
    }

    @Test
    void theCodeBeforeTheOneThatEnrolledIsNotAcceptedAfterIt() {
        Enrolment me = enrol(email);
        String previous = TestTotp.code(me.secret(), clock.instant(), -1);
        assertThat(delegates.login(email, PASSWORD, previous, null)).isEmpty();
    }

    @Test
    void aRecoveryCodeSignsInOnceInAnySpellingAndIsCounted() {
        Enrolment me = enrol(email);
        String code = me.recovery(3);
        assertThat(delegates.unusedRecoveryCodes(me.id())).isEqualTo(Delegates.RECOVERY_CODES);
        assertThat(delegates.login(email, PASSWORD, null, code.toLowerCase().replace("-", " "))).hasValue(me.id());
        assertThat(delegates.login(email, PASSWORD, null, code)).isEmpty();
        assertThat(delegates.unusedRecoveryCodes(me.id())).isEqualTo(Delegates.RECOVERY_CODES - 1);
    }

    @Test
    void nothingStoredIsACredential() {
        String invite = delegates.issueInvite(email, Delegates.Role.DELEGATE);
        Delegates.Staged staged = delegates.stage(invite, PASSWORD);
        Delegates.Enrolled enrolled = delegates.confirm(invite, now(staged.secret()));
        assertThat(jdbc.sql("select count(*) from delegate_invites where code_hash = ?").param(invite)
                .query(Integer.class).single()).isZero();
        byte[] stored = jdbc.sql("select totp_secret from delegates where id = ?").param(enrolled.delegateId())
                .query(byte[].class).single();
        assertThat(stored).isNotEqualTo(TestTotp.decode(staged.secret()));
        assertThat(box.open(stored)).isEqualTo(TestTotp.decode(staged.secret()));
        String raw = enrolled.recoveryCodes().get(0).replace("-", "");
        assertThat(jdbc.sql("select count(*) from delegate_recovery_codes where code_hash = ?").param(raw)
                .query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from delegate_recovery_codes where code_hash = ?").param(Codes.hash(raw))
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from delegate_invites where staged_totp_secret is not null "
                + "and code_hash = ?").param(Codes.hash(invite)).query(Integer.class).single())
                .as("the staged copy is cleared once spent").isZero();
    }

    @Test
    void suspensionEndsSignInAndEveryAssignmentAndReinstatementRestoresOnlyTheAccount() {
        Enrolment me = enrol(email);
        UUID store = store();
        stores.assign(store, me.id());
        assertThat(delegates.suspend(email)).isTrue();
        assertThat(delegates.active(me.id())).isEmpty();
        assertThat(stores.isAssigned(store, me.id())).isFalse();
        clock.advance(Duration.ofSeconds(Totp.STEP_SECONDS));
        assertThat(delegates.login(email, PASSWORD, now(me.secret()), null)).isEmpty();

        assertThat(delegates.reinstate(email)).isTrue();
        assertThat(delegates.active(me.id())).isPresent();
        assertThat(stores.assignedTo(me.id())).as("assignments are given again on purpose").isEmpty();
        assertThat(delegates.suspend("nobody@example.com")).isFalse();
    }

    @Test
    void aDelegateSeesOnlyTheStoresOpenlyAssignedToThem() {
        Enrolment me = enrol(email);
        Enrolment other = enrol("o-" + email);
        UUID mine = store();
        UUID theirs = store();
        assertThat(stores.assign(mine, me.id())).isTrue();
        assertThat(stores.assign(mine, me.id())).isFalse();
        stores.assign(theirs, other.id());
        assertThat(stores.assignedTo(me.id())).extracting(Stores.View::id).containsExactly(mine);
        assertThat(stores.unassign(mine, me.id())).isTrue();
        assertThat(stores.unassign(mine, me.id())).isFalse();
        assertThat(stores.assignedTo(me.id())).isEmpty();
        assertThat(jdbc.sql("select count(*) from store_assignments where store_id = ? and delegate_id = ?")
                .params(mine, me.id()).query(Integer.class).single()).as("ended, not deleted").isEqualTo(1);
    }

    // --- helpers ---------------------------------------------------------------------------------------------------

    private record Enrolment(UUID id, String secret, java.util.List<String> recoveryCodes) {
        String recovery(int i) {
            return recoveryCodes.get(i);
        }
    }

    private Enrolment enrol(String address) {
        String invite = delegates.issueInvite(address, Delegates.Role.DELEGATE);
        Delegates.Staged staged = delegates.stage(invite, PASSWORD);
        Delegates.Enrolled enrolled = delegates.confirm(invite, now(staged.secret()));
        return new Enrolment(enrolled.delegateId(), staged.secret(), enrolled.recoveryCodes());
    }

    private UUID store() {
        String seller = "s-" + UUID.randomUUID() + "@example.com";
        UUID account = accounts.redeem(accounts.issueInvite(seller), PASSWORD);
        return stores.add(account, Stores.Channel.NAVER, "데모 스토어");
    }

    private String now(String secret) {
        return TestTotp.code(secret, clock.instant(), 0);
    }

    /** A code three steps away — outside the window, so never accepted now. */
    private String wrong(String secret) {
        return TestTotp.code(secret, clock.instant(), 3);
    }
}
