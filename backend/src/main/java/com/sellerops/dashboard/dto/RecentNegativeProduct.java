package com.sellerops.dashboard.dto;

import java.time.LocalDate;
import java.util.UUID;

/**
 * One product's negative reviews <b>inside a window, against the window before it.</b>
 *
 * <p>Deliberately not {@link TopProductIssue}, which is the lifetime roll-up the legacy dashboard
 * summary serves and must keep serving. The two are different claims: that one answers 「이 가게에
 * 부정 리뷰가 몇 건인가」, this one answers 「최근 7일에 몇 건이 새로 생겼고, 그 전 7일보다 많은가」.
 * Printing the first under a heading that asks the second is the defect this record exists to end
 * (product-owner decision, 2026-10-01).
 *
 * <p><b>Both counts are measured, and the previous one may be zero.</b> A zero baseline is a fact —
 * nothing happened in that span — and is never withheld or treated as unknown: the window is the
 * same length either way and both are counts of rows this org holds.
 *
 * <p>{@code lastNegativeOn} is the latest receipt date of the reviews counted in {@code current},
 * so a consumer can say WHEN this was last seen without re-reading anything. It is null exactly
 * when {@code current} is zero.
 *
 * <p>{@code productName} may be null — never a placeholder, for the reason {@link TopProductIssue}
 * states: a review can point at a product the real-data-only catalogue read does not return.
 */
public record RecentNegativeProduct(UUID productId, String productName,
                                    long current, long previous,
                                    LocalDate periodStart, LocalDate periodEnd,
                                    LocalDate previousPeriodStart, LocalDate previousPeriodEnd,
                                    LocalDate lastNegativeOn) {
}
