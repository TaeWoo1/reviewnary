package com.sellerops.vendor.install;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerops.vendor.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** <b>A pending grant is approved once, handed over once, and gone after ten minutes</b> — contract §2. */
class InstallGrantsTest {

    private static final InstallGrants.Pending PC = new InstallGrants.Pending("Seller PC", "0.1.0", new byte[32], null);

    @Test
    void approvalIsSingleAndTheHandoverIsSingle() {
        InstallGrants grants = new InstallGrants(new MutableClock(Instant.parse("2026-10-17T00:00:00Z")));
        InstallGrants.Issued issued = issue(grants, "203.0.113.7");
        UUID account = UUID.randomUUID();
        assertThat(grants.poll(issued.deviceCode()).outcome()).isEqualTo(InstallGrants.PollOutcome.AUTHORIZATION_PENDING);
        assertThat(grants.approve(issued.userCode(), account)).hasValue("Seller PC");
        assertThat(grants.approve(issued.userCode(), UUID.randomUUID())).as("a second approver").isEmpty();
        InstallGrants.Poll poll = grants.poll(issued.deviceCode());
        assertThat(poll.outcome()).isEqualTo(InstallGrants.PollOutcome.APPROVED);
        assertThat(poll.accountId()).isEqualTo(account);
        assertThat(grants.poll(issued.deviceCode()).outcome()).isEqualTo(InstallGrants.PollOutcome.EXPIRED_TOKEN);
    }

    @Test
    void aGrantExpiresAtTenMinutesAndNotBefore() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-17T00:00:00Z"));
        InstallGrants grants = new InstallGrants(clock);
        InstallGrants.Issued kept = issue(grants, "203.0.113.7");
        InstallGrants.Issued lost = issue(grants, "203.0.113.7");
        clock.advance(InstallGrants.TTL.minus(Duration.ofSeconds(1)));
        assertThat(grants.approve(kept.userCode(), UUID.randomUUID())).isPresent();
        clock.advance(Duration.ofSeconds(1));
        assertThat(grants.approve(lost.userCode(), UUID.randomUUID())).isEmpty();
        assertThat(grants.poll(lost.deviceCode()).outcome()).isEqualTo(InstallGrants.PollOutcome.EXPIRED_TOKEN);
    }

    @Test
    void theUserCodeIgnoresCaseAndSeparatorsAndTheDeviceCodeDoesNotApprove() {
        InstallGrants grants = new InstallGrants(new MutableClock(Instant.parse("2026-10-17T00:00:00Z")));
        InstallGrants.Issued issued = issue(grants, "203.0.113.7");
        assertThat(grants.approve(issued.deviceCode(), UUID.randomUUID())).isEmpty();
        String typed = " " + issued.userCode().substring(0, 4).toLowerCase() + "-" + issued.userCode().substring(4) + " ";
        assertThat(grants.approve(typed, UUID.randomUUID())).isPresent();
    }

    @Test
    void peekingNeitherApprovesNorConsumes() {
        InstallGrants grants = new InstallGrants(new MutableClock(Instant.parse("2026-10-17T00:00:00Z")));
        InstallGrants.Issued issued = issue(grants, "203.0.113.7");
        assertThat(grants.peek(issued.userCode())).hasValueSatisfying(p -> assertThat(p.deviceName()).isEqualTo("Seller PC"));
        assertThat(grants.poll(issued.deviceCode()).outcome()).isEqualTo(InstallGrants.PollOutcome.AUTHORIZATION_PENDING);
        assertThat(grants.approve(issued.userCode(), UUID.randomUUID())).isPresent();
        assertThat(grants.peek(issued.userCode())).as("an approved grant is no longer offered").isEmpty();
    }

    @Test
    void oneAddressHoldsAtMostFivePendingAndPressingAgainDisplacesItsOldest() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-17T00:00:00Z"));
        InstallGrants grants = new InstallGrants(clock);
        InstallGrants.Issued first = issue(grants, "203.0.113.7");
        for (int i = 1; i < InstallGrants.MAX_PENDING_PER_SOURCE; i++) {
            clock.advance(Duration.ofSeconds(1));
            issue(grants, "203.0.113.7");
        }
        assertThat(grants.peek(first.userCode())).isPresent();
        clock.advance(Duration.ofSeconds(1));
        InstallGrants.Issued sixth = issue(grants, "203.0.113.7");
        assertThat(grants.peek(first.userCode())).as("the oldest made room").isEmpty();
        assertThat(grants.poll(first.deviceCode()).outcome()).isEqualTo(InstallGrants.PollOutcome.EXPIRED_TOKEN);
        assertThat(grants.peek(sixth.userCode())).isPresent();
    }

    @Test
    void anApprovedGrantAwaitingItsPollIsNeverDisplaced() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-17T00:00:00Z"));
        InstallGrants grants = new InstallGrants(clock);
        InstallGrants.Issued approved = issue(grants, "203.0.113.7");
        grants.approve(approved.userCode(), UUID.randomUUID());
        for (int i = 0; i < InstallGrants.MAX_PENDING_PER_SOURCE * 2; i++) {
            clock.advance(Duration.ofSeconds(1));
            issue(grants, "203.0.113.7");
        }
        assertThat(grants.poll(approved.deviceCode()).outcome()).isEqualTo(InstallGrants.PollOutcome.APPROVED);
    }

    @Test
    void oneAddressAskingTooOftenIsRefusedWithoutTouchingAnyoneElse() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-17T00:00:00Z"));
        InstallGrants grants = new InstallGrants(clock);
        for (int i = 0; i < InstallGrants.MAX_ISSUED_PER_SOURCE; i++) {
            issue(grants, "203.0.113.7");
        }
        assertThat(grants.issue(PC, "203.0.113.7").refusal()).isEqualTo(InstallGrants.Refusal.TOO_MANY);
        assertThat(grants.issue(PC, "198.51.100.9").issued()).as("another address").isNotNull();
        clock.advance(InstallGrants.TTL);
        assertThat(grants.issue(PC, "203.0.113.7").issued()).as("ten minutes later").isNotNull();
    }

    @Test
    void onlyManyAddressesTogetherCanFillTheWhole() {
        InstallGrants grants = new InstallGrants(new MutableClock(Instant.parse("2026-10-17T00:00:00Z")));
        for (int i = 0; i < InstallGrants.MAX_ISSUED_PER_SOURCE * 3; i++) {
            grants.issue(PC, "203.0.113.7");
        }
        assertThat(grants.issue(PC, "198.51.100.9").issued()).as("one address flooding").isNotNull();
        int addresses = 0;
        fill:
        while (true) {
            String address = "10.0." + (addresses / 256) + "." + (addresses % 256);
            addresses++;
            for (int i = 0; i < InstallGrants.MAX_PENDING_PER_SOURCE; i++) {
                if (grants.issue(PC, address).refusal() == InstallGrants.Refusal.BUSY) {
                    break fill;
                }
            }
        }
        assertThat(addresses).as("addresses it took to fill the whole")
                .isGreaterThan(InstallGrants.MAX_PENDING / InstallGrants.MAX_PENDING_PER_SOURCE - 2);
        assertThat(grants.issue(PC, "192.0.2.1").refusal()).isEqualTo(InstallGrants.Refusal.BUSY);
    }

    @Test
    void anIpv6CallerIsCountedByItsSlash64() {
        assertThat(InstallGrants.source("2001:db8:1:2:aaaa::1")).isEqualTo(InstallGrants.source("2001:db8:1:2:bbbb::9"));
        assertThat(InstallGrants.source("2001:db8:1:3::1")).isNotEqualTo(InstallGrants.source("2001:db8:1:2::1"));
        assertThat(InstallGrants.source("::ffff:203.0.113.7")).isEqualTo("203.0.113.7");
        assertThat(InstallGrants.source(" 203.0.113.7 ")).isEqualTo("203.0.113.7");
    }

    private static InstallGrants.Issued issue(InstallGrants grants, String source) {
        InstallGrants.Issue issue = grants.issue(PC, source);
        assertThat(issue.refusal()).isNull();
        return issue.issued();
    }
}
