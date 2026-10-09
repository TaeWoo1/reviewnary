package com.sellerops.autocheck;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>Which accounts this tick works, decided in one short transaction nobody else can overlap.</b>
 *
 * <p>Rows are read {@code for update skip locked} and their {@code next_check_at} is pushed one cadence out
 * before this transaction commits, so a second tick — or a second backend instance — sees nothing due and does
 * nothing, rather than reading the same store twice. The same arrangement, and the same known trade-off, as
 * {@code SyncScheduleClaimer}: the claim commits before the work runs, so a crash in between costs this account
 * one hour of automatic checking rather than causing a double read.
 */
@Component
public class ReviewAutoCheckClaimer {

    private final ReviewAutoCheckRepository rows;

    public ReviewAutoCheckClaimer(ReviewAutoCheckRepository rows) {
        this.rows = rows;
    }

    /**
     * One account's turn, and <b>which turn it is</b>.
     *
     * <p>{@code slot} is the instant this row was due for — the {@code next_check_at} that made it due, or the
     * tick's own clock when the row had none yet (a setting just switched on). It is the identity of this
     * execution, and the whole reason it is carried: the job's client id is derived from it, so a retry of
     * <b>this</b> turn converges on one job while the next hour's turn is a different turn and gets its own,
     * even though both read the same day.
     *
     * <p>A date would not do. 「ac-…-2026-10-09」 made every turn of one day the same ask, and the second turn
     * re-found the first turn's settled row and dispatched nothing — an automatic check that silently stopped
     * checking after its first read of the morning.
     */
    public record Claimed(ReviewAutoCheck row, Instant slot) {
    }

    @Transactional
    public List<Claimed> claimDue(Instant now, int limit) {
        List<Claimed> claimed = new ArrayList<>();
        for (ReviewAutoCheck row : rows.lockDue(now, limit)) {
            // Read before advancing: the value that MADE this row due is this turn's identity.
            Instant slot = row.getNextCheckAt() == null ? now : row.getNextCheckAt();
            int minutes = row.getIntervalMinutes() > 0
                    ? row.getIntervalMinutes() : ReviewAutoCheck.INTERVAL_MINUTES;
            row.setNextCheckAt(now.plus(Duration.ofMinutes(minutes)));
            claimed.add(new Claimed(rows.save(row), slot));
        }
        return claimed;
    }

    /**
     * <b>How long a sign-in wall is left alone before the product looks again.</b>
     *
     * <p>«A wall does not open with time» was right about the wall and wrong about the session. The wall is
     * the marketplace asking a person to sign in, and only they can; but the <b>session</b> comes back the
     * moment they sign in <em>anywhere</em> — in the product's own recovery window, in a tab they opened
     * themselves, on another device — and this product has no way to be told. On 2026-10-09 that was measured:
     * the seller signed in directly in their browser, the lane never learned, and it could not learn, because
     * the only thing that clears the wall is a successful read and the lane was refusing to read.
     *
     * <p>So the way out is to look again — rarely. Six hours is chosen to be unmistakably not a retry loop:
     * four attempts a day, each one a ten-second read that opens a page and is redirected to a login screen,
     * against an hourly cadence that would be noise about the one thing only the seller can resolve. A read is
     * its own probe, so nothing new happens on anyone's computer to support this.
     */
    public static final Duration AUTH_RETRY = Duration.ofHours(6);

    /** Record how this account's turn ended. Separate transaction: the read already happened either way. */
    @Transactional
    public void settle(ReviewAutoCheck claimed, Instant now, AutoCheckPause pause, boolean looked) {
        rows.findById(claimed.getId()).ifPresent(row -> {
            // Re-read rather than saving the claimed snapshot: the seller may have turned the setting off while
            // this turn was running, and their decision outranks anything this tick learned.
            if (looked) {
                row.setLastCheckAt(now);
            }
            row.setPausedReason(pause);
            if (pause == AutoCheckPause.PAUSED_AUTH) {
                // Hold off, but do not stop. PAUSED_DEVICE keeps the ordinary cadence on purpose: resolving a
                // desk costs nothing and touches no marketplace, so looking every hour there is free.
                row.setNextCheckAt(now.plus(AUTH_RETRY));
            }
            rows.save(row);
        });
    }
}
