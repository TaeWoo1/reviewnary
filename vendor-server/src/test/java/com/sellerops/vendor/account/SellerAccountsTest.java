package com.sellerops.vendor.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sellerops.vendor.MutableClock;
import com.sellerops.vendor.account.SellerAccounts.RedeemException;
import com.sellerops.vendor.account.SellerAccounts.RedeemFailure;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/** <b>An account exists only through one unexpired invite, once</b> — contract §2, D2. */
@SpringBootTest
@ActiveProfiles("test")
class SellerAccountsTest {

    private static final String PASSWORD = "a long enough password";

    @Autowired
    private JdbcClient jdbc;

    private MutableClock clock;
    private SellerAccounts accounts;
    private String email;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-10-17T00:00:00Z"));
        accounts = new SellerAccounts(jdbc, clock);
        email = "a-" + UUID.randomUUID() + "@example.com";
    }

    @Test
    void anInviteIsSpentOnceAndNeverStoredInPlainText() {
        String code = accounts.issueInvite(email);
        assertThat(jdbc.sql("select count(*) from seller_invites where code_hash = ?").param(code)
                .query(Integer.class).single()).isZero();
        accounts.redeem(code, PASSWORD);
        assertThatThrownBy(() -> accounts.redeem(code, PASSWORD))
                .isInstanceOfSatisfying(RedeemException.class,
                        e -> assertThat(e.failure()).isEqualTo(RedeemFailure.INVALID_INVITE));
    }

    @Test
    void anInviteExpiresAfterSevenDaysAndNotBefore() {
        String early = accounts.issueInvite(email);
        String late = accounts.issueInvite("b-" + email);
        clock.advance(SellerAccounts.INVITE_LIFETIME.minus(Duration.ofMinutes(1)));
        accounts.redeem(early, PASSWORD);
        clock.advance(Duration.ofMinutes(1));
        assertThatThrownBy(() -> accounts.redeem(late, PASSWORD)).isInstanceOf(RedeemException.class);
    }

    @Test
    void aShortPasswordLeavesTheInviteUnspent() {
        String code = accounts.issueInvite(email);
        assertThatThrownBy(() -> accounts.redeem(code, "short"))
                .isInstanceOfSatisfying(RedeemException.class,
                        e -> assertThat(e.failure()).isEqualTo(RedeemFailure.WEAK_PASSWORD));
        assertThat(accounts.redeem(code, PASSWORD)).isNotNull();
    }

    @Test
    void aSecondInviteForARegisteredAddressCreatesNoSecondAccount() {
        accounts.redeem(accounts.issueInvite(email), PASSWORD);
        String again = accounts.issueInvite(email.toUpperCase());
        assertThatThrownBy(() -> accounts.redeem(again, PASSWORD))
                .isInstanceOfSatisfying(RedeemException.class,
                        e -> assertThat(e.failure()).isEqualTo(RedeemFailure.ALREADY_REGISTERED));
    }

    @Test
    void loginNeedsTheRightPasswordAndAnActiveAccount() {
        UUID id = accounts.redeem(accounts.issueInvite(email), PASSWORD);
        assertThat(accounts.login(" " + email.toUpperCase() + " ", PASSWORD)).hasValue(id);
        assertThat(accounts.login(email, PASSWORD + "x")).isEmpty();
        assertThat(accounts.login("nobody@example.com", PASSWORD)).isEmpty();
        accounts.suspend(email);
        assertThat(accounts.login(email, PASSWORD)).isEmpty();
        assertThat(accounts.isActive(id)).isFalse();
        accounts.reinstate(email);
        assertThat(accounts.isActive(id)).isTrue();
    }

    @Test
    void thePasswordIsStoredOnlyAsABcryptHash() {
        UUID id = accounts.redeem(accounts.issueInvite(email), PASSWORD);
        String stored = jdbc.sql("select password_hash from seller_accounts where id = ?").param(id)
                .query(String.class).single();
        assertThat(stored).startsWith("$2").doesNotContain(PASSWORD);
    }
}
