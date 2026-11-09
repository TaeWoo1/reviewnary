package com.sellerops.connector.cafe24;

import java.time.Duration;
import java.util.List;

/**
 * <b>The open-inquiry recheck's numbers</b> (2026-11-08, inquiry_operational_truth_v1 §10.1 · §10.3 · §10.5). Every one of
 * them is a setting ({@link Cafe24RecheckProperties}), never a constant in the runner.
 *
 * <ul>
 *   <li>{@code dailyRequestLimit} — HTTP requests one seller account may spend on rechecks per Korean calendar day: the
 *       token refresh, every re-read, discovery and comment read, failed ones included.</li>
 *   <li>{@code retryAfterDays} — after the n-th failure in a row that was the inquiry's own, it is not asked about again
 *       until {@code retryAfterDays[n-1]} Korean days after that failure's day; past the end of the list, the last value.
 *       An account-side failure never makes an inquiry wait.</li>
 *   <li>{@code runLease} — how long one run holds its account without sending a request. A run that died lets go of
 *       the account this long after its last request.</li>
 *   <li>{@code accountWaitMinutes} — after the n-th run in a row that stopped on the account's side (a 429, a refused
 *       token or permission, no token, an outage), the account sends nothing for {@code accountWaitMinutes[n-1]} minutes;
 *       past the end of the list, the last value. A run that sent requests and ended without such a stop starts the count
 *       again.</li>
 * </ul>
 *
 * <p><b>{@link #PROVISIONAL} is a starting point for the pilot, not an operating value</b>: 20 requests a day, waits of 1,
 * 2, 4 and then 7 days, account waits of 2, 4 and then 8 hours. The operating values are a product and operations decision taken after the pilot's observation
 * (§10.3, §10.5).
 */
public record Cafe24RecheckPolicy(int dailyRequestLimit, List<Integer> retryAfterDays, Duration runLease,
                                  List<Integer> accountWaitMinutes) {

    /** Pilot starting account waits — provisional, see the class doc. */
    public static final List<Integer> PROVISIONAL_ACCOUNT_WAIT_MINUTES = List.of(120, 240, 480);

    /** Pilot starting values — provisional, see the class doc. */
    public static final Cafe24RecheckPolicy PROVISIONAL = new Cafe24RecheckPolicy(20, List.of(1, 2, 4, 7),
            Duration.ofMinutes(10), PROVISIONAL_ACCOUNT_WAIT_MINUTES);

    /** The same, with the provisional account waits. */
    public Cafe24RecheckPolicy(int dailyRequestLimit, List<Integer> retryAfterDays, Duration runLease) {
        this(dailyRequestLimit, retryAfterDays, runLease, PROVISIONAL_ACCOUNT_WAIT_MINUTES);
    }

    public Cafe24RecheckPolicy {
        if (dailyRequestLimit < 0) {
            throw new IllegalArgumentException("하루 요청 상한이 올바르지 않습니다.");
        }
        if (retryAfterDays == null || retryAfterDays.isEmpty()
                || retryAfterDays.stream().anyMatch(d -> d == null || d < 1)) {
            throw new IllegalArgumentException("재시도 간격은 1일 이상이어야 합니다.");
        }
        if (runLease == null || runLease.isNegative() || runLease.isZero()) {
            throw new IllegalArgumentException("실행 잠금 유지 시간이 올바르지 않습니다.");
        }
        if (accountWaitMinutes == null || accountWaitMinutes.isEmpty()
                || accountWaitMinutes.stream().anyMatch(m -> m == null || m < 1)) {
            throw new IllegalArgumentException("계정 대기 시간은 1분 이상이어야 합니다.");
        }
        retryAfterDays = List.copyOf(retryAfterDays);
        accountWaitMinutes = List.copyOf(accountWaitMinutes);
    }

    /** How long the account rests after its {@code failures}-th account-side stop in a row (at least the first). */
    public Duration accountWaitAfter(int failures) {
        int n = Math.max(1, failures);
        return Duration.ofMinutes(accountWaitMinutes.get(Math.min(n, accountWaitMinutes.size()) - 1));
    }

    /** Days to wait after the {@code failures}-th failure in a row; 0 when there is none. */
    public int waitDaysAfter(int failures) {
        if (failures <= 0) {
            return 0;
        }
        return retryAfterDays.get(Math.min(failures, retryAfterDays.size()) - 1);
    }
}
