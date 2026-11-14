import { describe, expect, it } from "vitest";
import { channelFigure, channelHealth, firstEmptyLine, kpiTally, sumTally, workReadWhole } from "./csHome";
import { tallyText } from "./homeBoard";
import type { ChannelCoverageRowView, ChannelMetricRow, MetricKpi, SellerAccountResponse } from "./types";

const NOW = new Date("2026-11-13T05:00:00Z");

function kpi(over: Partial<MetricKpi>): MetricKpi {
  return { key: "inquiries", label: "문의", value: 3, unit: "건", previousValue: null, deltaPercent: null, comparable: true, excludedChannels: 0, freshnessUnproven: false, ...over };
}

function row(over: Partial<ChannelMetricRow>): ChannelMetricRow {
  return {
    channelCode: "NAVER", channelNameKo: "네이버 스마트스토어", orderState: "OBSERVED_FRESH", revenue: 0, orders: 0, countedInOrders: true,
    inquiryState: "OBSERVED_FRESH", inquiries: 2, unansweredInquiries: 1, countedInInquiries: true, countedInUnansweredNow: true,
    reviewState: "OBSERVED_FRESH", reviews: 5, negativeReviews: 0, countedInReviews: true, connected: true, connectable: true,
    ...over,
  } as ChannelMetricRow;
}

function cov(over: Partial<ChannelCoverageRowView>): ChannelCoverageRowView {
  return {
    channelCode: "NAVER", channelNameKo: "네이버 스마트스토어", dataType: "REVIEW", state: "OBSERVED_FRESH", supported: true,
    verificationStatus: null, connected: true, connectionStatus: "CONNECTED", routineEnabled: true, routinePausedBy: null,
    lastSuccessfulSyncAt: "2026-11-13T04:50:00Z", latestAttemptAt: "2026-11-13T04:50:00Z", latestAttemptOutcome: "SUCCESS",
    rows: 1, openRows: null, newestObservedAt: null, ...over,
  };
}

const ACCOUNTS: SellerAccountResponse[] = [
  { id: "acc-naver", channelId: "c1", channelNameKo: "네이버 스마트스토어", alias: null, connectionStatus: "CONNECTED", lastSyncedAt: null, fileUpload: false },
];

describe("홈 CS 현황 — one way to write a number", () => {
  it("an overview figure is exact only when no channel was left out and freshness is proven", () => {
    expect(tallyText(kpiTally(kpi({})))).toBe("3");
    expect(tallyText(kpiTally(kpi({ excludedChannels: 1 })))).toBe("3+");
    expect(tallyText(kpiTally(kpi({ freshnessUnproven: true, value: 0 })))).toBe("—");
    expect(kpiTally(undefined)).toBeNull();
  });

  it("a sum is exact only if both parts are, and a missing part is a missing sum — never half of one", () => {
    expect(sumTally({ count: 3, atLeast: 3 }, { count: 2, atLeast: 2 })).toEqual({ count: 5, atLeast: 5 });
    expect(tallyText(sumTally({ count: 3, atLeast: 3 }, { count: null, atLeast: 2 }))).toBe("5+");
    expect(sumTally({ count: 3, atLeast: 3 }, null)).toBeNull();
  });

  it("a channel's figure: fresh → exact, counted but stale → floor, not counted → 「—」, unsupported → 「—」 and said so", () => {
    expect(tallyText(channelFigure(row({}), "reviews").tally)).toBe("5");
    expect(tallyText(channelFigure(row({ reviewState: "ZERO", reviews: 0 }), "reviews").tally)).toBe("0");
    expect(tallyText(channelFigure(row({ reviewState: "OBSERVED_FRESHNESS_UNPROVEN" }), "reviews").tally)).toBe("5+");
    expect(tallyText(channelFigure(row({ countedInReviews: false, reviews: 0, reviewState: "BLOCKED" }), "reviews").tally)).toBe("—");
    const unsupported = channelFigure(row({ reviewState: "NOT_SUPPORTED", reviews: 0, countedInReviews: false }), "reviews");
    expect(unsupported.unsupported).toBe(true);
    // 미답변 has its own verdict: a standing backlog counts even when today's window is silent.
    expect(tallyText(channelFigure(row({ countedInInquiries: false, inquiries: 0, inquiryState: "BLOCKED", unansweredInquiries: 36 }), "unanswered").tally)).toBe("36+");
  });
});

describe("홈 CS 현황 — which channel needs a look, and the existing way to fix it", () => {
  it("names the cause and sends the seller to the page that already owns the fix", () => {
    const at = (c: Partial<ChannelCoverageRowView>) => channelHealth(row({}), [cov({ dataType: "INQUIRY" }), cov(c)], ACCOUNTS, null, NOW);
    expect(at({ latestAttemptOutcome: "AUTH_REQUIRED" })).toMatchObject({ badge: "다시 연결 필요", action: { label: "다시 연결", to: "/connect/channels/acc-naver" } });
    expect(at({ routinePausedBy: "SYSTEM", routineEnabled: false })).toMatchObject({ badge: "수집 멈춤", action: { label: "확인하기" } });
    expect(at({ latestAttemptOutcome: "FAILED" })).toMatchObject({ badge: "확인하지 못함", line: "리뷰를 가져오지 못했어요", action: { label: "다시 가져오기" } });
    expect(at({ latestAttemptOutcome: "PARTIAL" })).toMatchObject({ badge: "일부만 확인", action: { label: "다시 가져오기" } });
    expect(at({})).toEqual({ tone: "ok", badge: null, line: "10분 전에 가져왔어요", action: null });
  });

  it("a failed read of a type the connector does not collect is still this channel's problem", () => {
    const h = channelHealth(row({}), [cov({ supported: false, latestAttemptOutcome: "FAILED" })], ACCOUNTS, null, NOW);
    expect(h.badge).toBe("확인하지 못함");
  });

  it("prefers the server's own gap link, and offers 연결하기 to a channel never connected", () => {
    const gap = { caseId: "g1", channelCode: "NAVER", channelNameKo: "네이버", reason: "SOURCE_SIGN_IN_REQUIRED", dataTypes: ["REVIEW"], since: "2026-11-12T10:00:00Z", lastSeenAt: "2026-11-13T04:00:00Z", to: "/customer-operations/cases/g1" };
    expect(channelHealth(row({}), [cov({})], ACCOUNTS, [gap], NOW).action).toEqual({ label: "로그인", to: "/customer-operations/cases/g1" });
    expect(channelHealth(row({ connected: false }), [], ACCOUNTS, null, NOW)).toMatchObject({ tone: "off", action: { label: "연결하기", to: "/connect" } });
  });

  it("never starts anything: every action is a link to an existing page", () => {
    const h = channelHealth(row({}), [cov({ latestAttemptOutcome: "FAILED" })], ACCOUNTS, null, NOW);
    expect(Object.keys(h.action ?? {})).toEqual(["label", "to"]);
  });
});

describe("홈 CS 현황 — an empty list proves nothing it did not read", () => {
  const whole = { name: "CASES" as const, read: true, complete: true };
  it("is whole only when every source was read to the end and the total is exact", () => {
    expect(workReadWhole({ total: { count: 0, atLeast: 0 }, sources: [whole] })).toBe(true);
    expect(workReadWhole({ total: { count: 0, atLeast: 0 }, sources: [whole, { name: "REVIEWS", read: true, complete: false }] })).toBe(false);
    expect(workReadWhole({ total: { count: 0, atLeast: 0 }, sources: [{ name: "INQUIRIES", read: false, complete: false }] })).toBe(false);
    expect(workReadWhole({ total: { count: null, atLeast: 0 }, sources: [whole] })).toBe(false);
    expect(workReadWhole(null)).toBe(false);
  });

  it("a partial read outranks a channel problem, and only a whole, clean read says there is nothing to do", () => {
    expect(firstEmptyLine(false, false)).toContain("다 불러오지 못했어요");
    expect(firstEmptyLine(false, true)).toContain("다 불러오지 못했어요");
    expect(firstEmptyLine(true, true)).toContain("아직 확인하지 못한 자료에 남은 일이 있을 수 있어요");
    expect(firstEmptyLine(true, false)).toContain("지금 할 일이 없어요");
  });
});
