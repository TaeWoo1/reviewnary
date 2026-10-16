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
        InstallGrants.Issued issued = grants.issue(PC).orElseThrow();
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
        InstallGrants.Issued kept = grants.issue(PC).orElseThrow();
        InstallGrants.Issued lost = grants.issue(PC).orElseThrow();
        clock.advance(InstallGrants.TTL.minus(Duration.ofSeconds(1)));
        assertThat(grants.approve(kept.userCode(), UUID.randomUUID())).isPresent();
        clock.advance(Duration.ofSeconds(1));
        assertThat(grants.approve(lost.userCode(), UUID.randomUUID())).isEmpty();
        assertThat(grants.poll(lost.deviceCode()).outcome()).isEqualTo(InstallGrants.PollOutcome.EXPIRED_TOKEN);
    }

    @Test
    void theUserCodeIgnoresCaseAndSeparatorsAndTheDeviceCodeDoesNotApprove() {
        InstallGrants grants = new InstallGrants(new MutableClock(Instant.parse("2026-10-17T00:00:00Z")));
        InstallGrants.Issued issued = grants.issue(PC).orElseThrow();
        assertThat(grants.approve(issued.deviceCode(), UUID.randomUUID())).isEmpty();
        String typed = " " + issued.userCode().substring(0, 4).toLowerCase() + "-" + issued.userCode().substring(4) + " ";
        assertThat(grants.approve(typed, UUID.randomUUID())).isPresent();
    }

    @Test
    void peekingNeitherApprovesNorConsumes() {
        InstallGrants grants = new InstallGrants(new MutableClock(Instant.parse("2026-10-17T00:00:00Z")));
        InstallGrants.Issued issued = grants.issue(PC).orElseThrow();
        assertThat(grants.peek(issued.userCode())).hasValueSatisfying(p -> assertThat(p.deviceName()).isEqualTo("Seller PC"));
        assertThat(grants.poll(issued.deviceCode()).outcome()).isEqualTo(InstallGrants.PollOutcome.AUTHORIZATION_PENDING);
        assertThat(grants.approve(issued.userCode(), UUID.randomUUID())).isPresent();
        assertThat(grants.peek(issued.userCode())).as("an approved grant is no longer offered").isEmpty();
    }

    @Test
    void pendingGrantsAreCapped() {
        InstallGrants grants = new InstallGrants(new MutableClock(Instant.parse("2026-10-17T00:00:00Z")));
        for (int i = 0; i < InstallGrants.MAX_PENDING; i++) {
            assertThat(grants.issue(PC)).isPresent();
        }
        assertThat(grants.issue(PC)).isEmpty();
    }
}
