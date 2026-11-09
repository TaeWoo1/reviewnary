package com.sellerops.connector.cafe24;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * {@code sellerops.inquiry.recheck.*} — the open-inquiry recheck's settings (2026-11-08). The defaults are
 * {@link Cafe24RecheckPolicy#PROVISIONAL}, the pilot's starting point and not an operating value.
 *
 * <p>Settings only — nothing here runs a recheck. Whether a recheck runs after each scheduled collection is a separate
 * flag, {@code sellerops.inquiry.recheck.auto-enabled}, off unless set ({@link Cafe24AnswerRecheckConfiguration}).
 */
@Component
public class Cafe24RecheckProperties {

    private final Cafe24RecheckPolicy policy;

    public Cafe24RecheckProperties(
            @Value("${sellerops.inquiry.recheck.daily-request-limit:20}") int dailyRequestLimit,
            @Value("${sellerops.inquiry.recheck.retry-after-days:1,2,4,7}") String retryAfterDays,
            @Value("${sellerops.inquiry.recheck.run-lease-minutes:10}") long runLeaseMinutes,
            @Value("${sellerops.inquiry.recheck.account-wait-minutes:120,240,480}") String accountWaitMinutes) {
        this.policy = new Cafe24RecheckPolicy(dailyRequestLimit, parseDays(retryAfterDays),
                Duration.ofMinutes(runLeaseMinutes), parseList(accountWaitMinutes, "계정 대기 시간 설정을 읽을 수 없습니다."));
    }

    public Cafe24RecheckPolicy policy() {
        return policy;
    }

    static List<Integer> parseDays(String csv) {
        return parseList(csv, "재시도 간격 설정을 읽을 수 없습니다.");
    }

    static List<Integer> parseList(String csv, String unreadable) {
        try {
            return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).map(Integer::valueOf)
                    .toList();
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(unreadable);
        }
    }
}
