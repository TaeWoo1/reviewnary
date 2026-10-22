package com.sellerops.vendor.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sellerops.vendor.MutableClock;
import com.sellerops.vendor.account.SellerAccounts;
import com.sellerops.vendor.auth.StepUps.Purpose;
import com.sellerops.vendor.auth.StepUps.Refusal;
import com.sellerops.vendor.mail.DevOutboxMailer;
import com.sellerops.vendor.mail.NoopMailer;
import com.sellerops.vendor.mail.OutboundMail;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/**
 * <b>A step-up code is one account's, one session's, one request's, and used once; wrong tries are not forgiven by
 * asking again</b> — contract §2 (2026-10-22 review conditions).
 */
@SpringBootTest
@ActiveProfiles("test")
class StepUpsTest {

    private static final String SESSION = "session-a";

    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private SellerAccounts accounts;

    private MutableClock clock;
    private DevOutboxMailer mailer;
    private StepUps stepUps;
    private String email;
    private UUID account;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-10-22T03:00:00Z"));
        mailer = new DevOutboxMailer();
        stepUps = new StepUps(jdbc, mailer, clock);
        email = "su-" + UUID.randomUUID() + "@example.com";
        account = accounts.redeem(accounts.issueInvite(email), "a long enough password");
    }

    @Test
    void aVerifiedCodeIsSpentOnceForItsOwnRequestWithinTenMinutes() {
        String code = send("PC1");
        assertThat(stepUps.spend(account, SESSION, Purpose.PC_LINK, "PC1")).as("sent, not verified").isFalse();
        stepUps.verify(account, SESSION, Purpose.PC_LINK, "PC1", code);
        assertThat(stepUps.spend(account, SESSION, Purpose.PC_LINK, "PC2")).isFalse();
        assertThat(stepUps.spend(account, "session-b", Purpose.PC_LINK, "PC1")).isFalse();
        assertThat(stepUps.spend(account, SESSION, Purpose.PC_LINK, "PC1")).isTrue();
        assertThat(stepUps.spend(account, SESSION, Purpose.PC_LINK, "PC1")).as("once").isFalse();

        String late = send("PC3");
        stepUps.verify(account, SESSION, Purpose.PC_LINK, "PC3", late);
        clock.advance(StepUps.TTL.plusSeconds(1));
        assertThat(stepUps.spend(account, SESSION, Purpose.PC_LINK, "PC3")).as("verified too long ago").isFalse();
    }

    @Test
    void anUnusedCodeRunsOutAndOnlyTheNewestWorks() {
        String first = send("PC1");
        String second = send("PC1");
        if (!first.equals(second)) {
            refused(() -> stepUps.verify(account, SESSION, Purpose.PC_LINK, "PC1", first), Refusal.WRONG_CODE);
        }
        clock.advance(StepUps.TTL.plusSeconds(1));
        refused(() -> stepUps.verify(account, SESSION, Purpose.PC_LINK, "PC1", second), Refusal.EXPIRED);
    }

    @Test
    void wrongTriesAddUpAcrossCodesOfTheSameRequest() {
        String code = send("PC1");
        String wrong = code.equals("000000") ? "111111" : "000000";
        for (int i = 0; i < 4; i++) {
            refused(() -> stepUps.verify(account, SESSION, Purpose.PC_LINK, "PC1", wrong), Refusal.WRONG_CODE);
        }
        String again = send("PC1");
        String wrongAgain = again.equals("000000") ? "111111" : "000000";
        refused(() -> stepUps.verify(account, SESSION, Purpose.PC_LINK, "PC1", wrongAgain), Refusal.LOCKED);
        refused(() -> stepUps.verify(account, SESSION, Purpose.PC_LINK, "PC1", again), Refusal.LOCKED);
        refused(() -> send("PC1"), Refusal.LOCKED);
        assertThat(stepUps.spend(account, SESSION, Purpose.PC_LINK, "PC1")).isFalse();
    }

    @Test
    void sendingIsCappedPerRequestAndPerAccountHour() {
        for (int i = 0; i < StepUps.MAX_CODES_PER_REQUEST; i++) {
            send("PC1");
        }
        refused(() -> send("PC1"), Refusal.TOO_MANY);
        for (int i = StepUps.MAX_CODES_PER_REQUEST; i < StepUps.MAX_CODES_PER_ACCOUNT; i++) {
            send("PC" + (100 + i));
        }
        refused(() -> send("PC999"), Refusal.TOO_MANY);
        clock.advance(StepUps.ACCOUNT_WINDOW.plus(Duration.ofMinutes(1)));
        send("PC999");
    }

    @Test
    void theAccountsWrongTriesInAnHourLockEveryRequest() {
        for (int r = 0; r < 2; r++) {
            String target = "PC" + r;
            String code = send(target);
            String wrong = code.equals("000000") ? "111111" : "000000";
            for (int i = 0; i < StepUps.MAX_FAILURES_PER_ACCOUNT / 2; i++) {
                try {
                    stepUps.verify(account, SESSION, Purpose.PC_LINK, target, wrong);
                } catch (StepUps.StepUpException e) {
                    assertThat(e.refusal()).isIn(Refusal.WRONG_CODE, Refusal.LOCKED);
                }
            }
        }
        refused(() -> send("PC-fresh"), Refusal.LOCKED);
    }

    @Test
    void noMailNoCodeAndTheAddressIsMasked() {
        StepUps off = new StepUps(jdbc, new NoopMailer(), clock);
        refused(() -> off.send(account, SESSION, Purpose.PC_LINK, "PC1", (to, c) -> new OutboundMail(to, "s", c)),
                Refusal.MAIL_OFF);
        assertThat(StepUps.mask("seller@example.com")).isEqualTo("se***@example.com");
        assertThat(StepUps.mask("a@example.com")).isEqualTo("a***@example.com");
    }

    private String send(String target) {
        stepUps.send(account, SESSION, Purpose.PC_LINK, target, (to, code) -> new OutboundMail(to, "code", "코드 " + code));
        List<OutboundMail> mine = mailer.outbox().stream().filter(m -> m.to().equals(email)).toList();
        Matcher m = Pattern.compile("코드 (\\d{6})").matcher(mine.get(mine.size() - 1).text());
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    private static void refused(Runnable call, Refusal refusal) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(StepUps.StepUpException.class,
                e -> assertThat(e.refusal()).isEqualTo(refusal));
    }
}
