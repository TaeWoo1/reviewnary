package com.sellerops.dashboard.insights.dto;

import java.time.LocalDate;

/**
 * One thing worth looking at, with somewhere to go.
 *
 * <p><b>Derived, not written.</b> These are computed from rows this backend already holds — no model
 * is called and no quota is spent, which is also why the dashboard keeps working when the Agent's
 * daily budget is gone ({@code docs/demo_core_experience_v1.md} §7). Naming them "AI Insights" on
 * screen is fine; claiming a model produced them would not be.
 *
 * <p><b>An insight with no evidence does not exist.</b> Every producer returns nothing when its
 * condition is not met, rather than a card saying "특이사항 없음" — a screen full of reassurance
 * nobody measured is worse than a shorter list.
 *
 * <p>{@code to} is an in-app route; {@code agentGoal}, when present, is a question the seller can
 * hand to the Agent as-is. The goal is a suggestion for a human to send, never something dispatched
 * on its own.
 *
 * <h2>When an insight is about — and what that does NOT prove</h2>
 *
 * <p>(Product-owner decisions, 2026-10-01.) Before these fields the record carried a sentence and a
 * destination and nothing else, so a consumer could not tell a change from a standing state — and
 * the Home's 「오늘 달라진 점」 printed a five-month lifetime roll-up under a heading that says 오늘.
 *
 * <ul>
 *   <li>{@code periodStart}/{@code periodEnd} — the window the claim is measured over, or BOTH null
 *       for a present-state fact (a backlog, a broken connection). Null is not 「unknown」: it means
 *       the claim is about now and a window would be a fiction.</li>
 *   <li>{@code previousPeriodStart}/{@code previousPeriodEnd} — the window it is compared AGAINST.
 *       <b>Only an insight that states all four is one whose change is proven by comparison</b>, and
 *       that is the structural property a 「무엇이 달라졌나」 surface filters on. A producer that
 *       measures one window and no baseline states a level, not a change, and leaves these null —
 *       which is {@code MetricPeriod}'s own rule («a delta with an unnamed baseline is not a
 *       measurement») applied to the record rather than to the prose.</li>
 *   <li>{@code observedAt} — the calendar date the underlying fact was last OBSERVED. Never null.
 *       <b>It is an observation time and never a change timestamp.</b> A connector that has been
 *       disconnected for three weeks is observed to be disconnected again every time we read, and
 *       reading it today does not make it a thing that changed today. No surface may use
 *       {@code observedAt == today} as evidence that something changed; it exists to date the
 *       evidence, so a reader can tell how stale a finding is.</li>
 * </ul>
 *
 * <p>Dates here are the dates the rows behind them carry. A producer that reads review receipt dates
 * reports one of those; a producer measuring the present reports the window's own last day. Neither
 * is {@code LocalDate.now()} called a second time inside the service.
 */
public record OperationsInsight(String key, Severity severity, String title, String detail,
                                String to, String actionLabel, String agentGoal,
                                LocalDate periodStart, LocalDate periodEnd,
                                LocalDate previousPeriodStart, LocalDate previousPeriodEnd,
                                LocalDate observedAt) {

    /** Ordering only — the screen decides colour. */
    public enum Severity { ATTENTION, WATCH, INFO }
}
