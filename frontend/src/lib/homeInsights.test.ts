import { describe, expect, it } from "vitest";
import { NO_CHANGE_TODAY, changeInsights } from "./homeInsights";
import type { OperationsInsight } from "./types";

/**
 * <b>오늘 달라진 점 shows changes, and a change is two windows compared</b> (product-owner decision,
 * 2026-10-01).
 *
 * <p>Two regressions are pinned here because both were on screen. The first was a five-month lifetime
 * roll-up under a heading that says 오늘. The second was the fix for it going one step short —
 * filtering on 「observed today」, which a connector that has been disconnected for three weeks
 * satisfies every single morning. These assert the structural rule that replaced both: a producer
 * that measured no baseline is stating a level, and a level is not news.
 */

const insight = (over: Partial<OperationsInsight>): OperationsInsight => ({
  key: "K",
  severity: "ATTENTION",
  title: "제목",
  detail: null,
  to: "/overview",
  actionLabel: "열기",
  agentGoal: null,
  // The shape of a real change: 최근 7일, measured against 이전 7일.
  periodStart: "2026-09-25",
  periodEnd: "2026-10-01",
  previousPeriodStart: "2026-09-18",
  previousPeriodEnd: "2026-09-24",
  observedAt: "2026-10-01",
  ...over,
});

// 2026-10-01 09:00 KST — and 2026-09-30 in UTC, which is exactly why the day key is KST's.
const now = new Date("2026-10-01T09:00:00+09:00");

describe("changeInsights", () => {
  it("keeps an insight that states its own window AND the one it was compared against", () => {
    expect(changeInsights([insight({ key: "7d" })], now)?.map((i) => i.key)).toEqual(["7d"]);
  });

  it("drops the lifetime roll-up — the first row that was on screen", () => {
    const lifetime = insight({
      key: "NEGATIVE_REVIEW_PRODUCT",
      title: "원터치 디스펜서… 부정 리뷰 3건",
      periodStart: "2025-11-01",
      periodEnd: "2026-03-06",
      previousPeriodStart: null,
      previousPeriodEnd: null,
      observedAt: "2026-03-06",
    });
    expect(changeInsights([lifetime], now)).toEqual([]);
  });

  it("drops a disconnected connector even though it is observed today — the SECOND row that was on screen", () => {
    // 「G마켓/옥션 연결이 끊겼습니다」. True, observed this morning, and not a thing that changed this
    // morning: it has been disconnected for weeks and we look again every read. Collection health is
    // 채널 상태's, alone.
    const broken = insight({
      key: "CHANNEL_NOT_REPORTING",
      title: "G마켓/옥션 연결이 끊겼습니다",
      periodStart: null,
      periodEnd: null,
      previousPeriodStart: null,
      previousPeriodEnd: null,
      observedAt: "2026-10-01",
    });
    expect(changeInsights([broken], now)).toEqual([]);
  });

  it("drops a present-state backlog — a level measured now is not a movement", () => {
    const backlog = insight({
      key: "INQUIRY_BACKLOG",
      title: "답변이 필요한 문의 46건",
      periodStart: null, periodEnd: null, previousPeriodStart: null, previousPeriodEnd: null,
    });
    expect(changeInsights([backlog], now)).toEqual([]);
  });

  it("drops a share measured inside ONE window — a baseline is what makes it a change", () => {
    // 「네이버가 매출의 72%」: the window is stated because the figure depends on it, and nothing is
    // compared. `MetricPeriod`'s own rule — a delta with an unnamed baseline is not a measurement.
    const share = insight({ key: "CHANNEL_CONCENTRATION", previousPeriodStart: null, previousPeriodEnd: null });
    expect(changeInsights([share], now)).toEqual([]);
  });

  it("never lets observedAt alone admit anything", () => {
    // Every period field null, observed today. Under the rule this replaced, this was shown.
    const observedOnly = insight({
      periodStart: null, periodEnd: null, previousPeriodStart: null, previousPeriodEnd: null,
      observedAt: "2026-10-01",
    });
    expect(changeInsights([observedOnly], now)).toEqual([]);
  });

  it("requires the window to be the CURRENT one, read off its boundary and not off an observation", () => {
    // `periodEnd` is `LocalDate.now(KST)` when the metrics are computed, so a response held from
    // yesterday carries yesterday's — the same guard `todayInflow` applies to its series.
    const held = insight({ periodEnd: "2026-09-30", previousPeriodEnd: "2026-09-23" });
    expect(changeInsights([held], now)).toEqual([]);
    // And the boundary is read in KST: 00:30 on the 1st in Korea is still 2026-09-30 in UTC.
    const justAfterMidnight = new Date("2026-10-01T00:30:00+09:00");
    expect(changeInsights([insight({})], justAfterMidnight)).toHaveLength(1);
  });

  it("distinguishes 「변화 없음」 from 「읽지 못함」 — a failed read is null, an empty day is []", () => {
    expect(changeInsights(null, now)).toBeNull();
    expect(changeInsights(undefined, now)).toBeNull();
    expect(changeInsights([insight({ previousPeriodStart: null, previousPeriodEnd: null })], now)).toEqual([]);
  });

  it("filters only — it never re-dates, re-ranks or re-words what it keeps", () => {
    const rows = [insight({ key: "a", severity: "WATCH" }), insight({ key: "b" })];
    expect(changeInsights(rows, now)).toEqual(rows);
  });

  it("names the empty day without claiming anything about tomorrow", () => {
    // A full stop: it is the section's whole body now, not a clause beside others.
    expect(NO_CHANGE_TODAY).toBe("오늘 새로 확인된 변화는 없습니다.");
  });
});
