package com.sellerops.inquiry.recheck;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntFunction;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * <b>What the open-inquiry recheck must remember across runs and restarts</b> (V185, 2026-11-08, inquiry_operational_truth_v1
 * §10.0 · §10.3): how many requests one seller account has spent today, and which run holds that account now. Both live in
 * the database, so a restarted server, a second call the same day and a second server all see the same numbers.
 *
 * <p><b>The day is the Korean calendar day</b> ({@link #DAY_ZONE}), taken from the clock at the moment each request is
 * taken — a run that crosses midnight spends its later requests from the new day.
 *
 * <p><b>And which account must rest</b> (V186): after a run stopped on the account's side — a 429, a refused token or
 * permission, no token, an outage — the account sends nothing until a wait has passed, so the next scheduled collection's
 * end does not spend requests on the same failure ({@link #accountWaitUntil}).
 *
 * <p><b>Nothing is planned here.</b> The limit, the lease length and the retry intervals are the caller's settings
 * ({@code Cafe24RecheckPolicy}); this class only counts and locks. Not for use inside a caller's transaction: each call is
 * its own statement, so a refused key insert by a racer never leaves someone else's transaction rolled back.
 */
public final class InquiryRecheckLedger {

    /** The calendar the daily budget and the retry days are counted in. */
    public static final ZoneId DAY_ZONE = ZoneId.of("Asia/Seoul");

    /** This run's hold on one account. */
    public record Lease(UUID sellerAccountId, UUID holder, Duration length) {
    }

    private final InquiryRecheckDailyUsageRepository usage;
    private final InquiryRecheckRunLockRepository locks;
    private final InquiryRecheckAccountWaitRepository waits;
    private final Clock clock;

    public InquiryRecheckLedger(InquiryRecheckDailyUsageRepository usage, InquiryRecheckRunLockRepository locks,
                                InquiryRecheckAccountWaitRepository waits, Clock clock) {
        this.usage = usage;
        this.locks = locks;
        this.waits = waits;
        this.clock = clock;
    }

    public LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), DAY_ZONE);
    }

    // ---- the day's requests -------------------------------------------------------------------------------------

    /**
     * Take one request out of today's {@code dailyLimit} for this account, before it is sent. False: the day is spent, and
     * the request must not go out.
     */
    public boolean take(UUID sellerAccountId, int dailyLimit) {
        if (dailyLimit <= 0) {
            return false;
        }
        Instant now = clock.instant();
        LocalDate day = LocalDate.ofInstant(now, DAY_ZONE);
        if (usage.take(sellerAccountId, day, dailyLimit, now) == 1) {
            return true;
        }
        if (usage.requestsOn(sellerAccountId, day).isPresent()) {
            return false; // the row is there and at the limit
        }
        try {
            usage.openDay(sellerAccountId, day, now);
        } catch (DataIntegrityViolationException racedIn) {
            // Another run opened the same day at the same moment: the row is there now, which is all that was wanted.
        }
        return usage.take(sellerAccountId, day, dailyLimit, now) == 1;
    }

    /** How many of today's {@code dailyLimit} requests are left for this account. */
    public int remainingToday(UUID sellerAccountId, int dailyLimit) {
        return Math.max(0, dailyLimit - usedOn(sellerAccountId, today()));
    }

    public int usedOn(UUID sellerAccountId, LocalDate day) {
        return usage.requestsOn(sellerAccountId, day).orElse(0);
    }

    // ---- one run per account ------------------------------------------------------------------------------------

    /**
     * Hold this account for one run, for {@code length} from now. Empty: another run holds it and its lease has not run
     * out. A lease that has run out — its holder died, or stalled past it — is taken over.
     */
    public Optional<Lease> acquire(UUID sellerAccountId, Duration length) {
        if (length == null || length.isNegative() || length.isZero()) {
            throw new IllegalArgumentException("잠금 유지 시간이 올바르지 않습니다.");
        }
        UUID holder = UUID.randomUUID();
        Instant now = clock.instant();
        Instant until = now.plus(length);
        int taken;
        try {
            taken = locks.takeFree(sellerAccountId, holder, now, until);
        } catch (DataIntegrityViolationException racedIn) {
            taken = 0; // another run inserted the same account's row at the same moment: it holds it
        }
        if (taken == 0) {
            taken = locks.takeOverExpired(sellerAccountId, holder, now, until);
        }
        return taken == 1 ? Optional.of(new Lease(sellerAccountId, holder, length)) : Optional.empty();
    }

    /** Keep holding for another {@code lease.length()}. False: the lease ran out or was taken over — stop sending. */
    public boolean renew(Lease lease) {
        Instant now = clock.instant();
        return locks.renew(lease.sellerAccountId(), lease.holder(), now, now.plus(lease.length())) == 1;
    }

    /** Let go. A lease another run has taken over in the meantime is left alone. */
    public void release(Lease lease) {
        locks.release(lease.sellerAccountId(), lease.holder());
    }

    // ---- the account's wait after an account-side stop ---------------------------------------------------------

    /** Until when this account must send nothing; empty when it need not wait now. */
    public Optional<Instant> accountWaitUntil(UUID sellerAccountId) {
        Instant now = clock.instant();
        return waits.waitUntilOf(sellerAccountId).filter(now::isBefore);
    }

    /**
     * A run on this account stopped on the account's side ({@code stop}, the run's stop name). Adds one to the stops in a
     * row and makes the account wait {@code waitAfter.apply(n)} from now, {@code n} being that new count. Returns
     * {@code n} — or {@code 0}, with nothing written, when {@code lease} no longer holds the account: a run that stalled
     * past its lease and was taken over must not lengthen the wait the run that took over has recorded (2026-11-09).
     * The holder check and the change are one statement; if two holders' writes ever met, the count moves only from the
     * value read.
     */
    public int recordAccountFailure(Lease lease, String stop, IntFunction<Duration> waitAfter) {
        UUID account = lease.sellerAccountId();
        Instant now = clock.instant();
        for (int attempt = 0; attempt < 3; attempt++) {
            Optional<Integer> seen = waits.failuresOf(account);
            if (seen.isEmpty()) {
                int opened;
                try {
                    opened = waits.openWait(account, lease.holder(), stop, now, now.plus(waitAfter.apply(1)));
                } catch (DataIntegrityViolationException racedIn) {
                    opened = 0;
                }
                if (opened == 1) {
                    return 1;
                }
            } else {
                int next = seen.get() + 1;
                if (waits.addFailure(account, lease.holder(), seen.get(), next, stop, now,
                        now.plus(waitAfter.apply(next))) == 1) {
                    return next;
                }
            }
            if (!holds(lease, now)) {
                return 0;
            }
        }
        throw new IllegalStateException("계정 대기를 기록하지 못했습니다.");
    }

    /**
     * The account answered again: no more waiting, and the next account-side stop counts from one. False, with nothing
     * changed, when {@code lease} no longer holds the account — a run that was taken over leaves the new holder's wait.
     */
    public boolean clearAccountWait(Lease lease) {
        Instant now = clock.instant();
        return waits.clear(lease.sellerAccountId(), lease.holder(), now) > 0 || holds(lease, now);
    }

    private boolean holds(Lease lease, Instant now) {
        return locks.countHeld(lease.sellerAccountId(), lease.holder(), now) > 0;
    }

    /** How many runs in a row stopped on this account's side; 0 when it is not waiting out a streak. */
    public int accountFailures(UUID sellerAccountId) {
        return waits.failuresOf(sellerAccountId).orElse(0);
    }
}
