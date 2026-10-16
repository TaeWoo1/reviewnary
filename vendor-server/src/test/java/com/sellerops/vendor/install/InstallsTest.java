package com.sellerops.vendor.install;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerops.vendor.MutableClock;
import com.sellerops.vendor.account.SellerAccounts;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/** <b>An install token lives 180 days, is stored only as a hash, and dies with revocation</b> — contract §2. */
@SpringBootTest
@ActiveProfiles("test")
class InstallsTest {

    @Autowired
    private JdbcClient jdbc;

    @Test
    void theTokenExpiresAfterItsLifetimeAndIsNeverStoredInPlainText() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-17T00:00:00Z"));
        SellerAccounts accounts = new SellerAccounts(jdbc, clock);
        Installs installs = new Installs(new InstallGrants(clock), accounts, jdbc, clock);
        UUID account = accounts.redeem(accounts.issueInvite("i-" + UUID.randomUUID() + "@example.com"),
                "a long enough password");
        byte[] key = new byte[32];
        key[0] = 9;
        InstallGrants.Issued issued = installs.start("PC", null, Base64.getEncoder().encodeToString(key));
        installs.approve(account, issued.userCode());
        Installs.Redeemed redeemed = installs.redeem(issued.deviceCode()).redeemed();

        assertThat(jdbc.sql("select count(*) from installs where token_hash = ?").param(redeemed.token())
                .query(Integer.class).single()).isZero();
        assertThat(installs.authenticate(redeemed.token())).isPresent();
        clock.advance(Installs.TOKEN_LIFETIME.minus(Duration.ofMinutes(1)));
        assertThat(installs.authenticate(redeemed.token())).isPresent();
        clock.advance(Duration.ofMinutes(1));
        assertThat(installs.authenticate(redeemed.token())).isEmpty();
    }

    @Test
    void aSuspendedAccountCannotApproveOrRedeem() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-17T00:00:00Z"));
        SellerAccounts accounts = new SellerAccounts(jdbc, clock);
        Installs installs = new Installs(new InstallGrants(clock), accounts, jdbc, clock);
        String email = "s-" + UUID.randomUUID() + "@example.com";
        UUID account = accounts.redeem(accounts.issueInvite(email), "a long enough password");
        byte[] key = new byte[32];
        key[0] = 9;
        String encoded = Base64.getEncoder().encodeToString(key);

        InstallGrants.Issued approvedThenSuspended = installs.start("PC", null, encoded);
        assertThat(installs.approve(account, approvedThenSuspended.userCode())).isPresent();
        accounts.suspend(email);
        assertThat(installs.redeem(approvedThenSuspended.deviceCode()).outcome())
                .isEqualTo(InstallGrants.PollOutcome.EXPIRED_TOKEN);
        InstallGrants.Issued whileSuspended = installs.start("PC", null, encoded);
        assertThat(installs.approve(account, whileSuspended.userCode())).isEmpty();
    }
}
