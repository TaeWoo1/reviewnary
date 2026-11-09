import { describe, expect, it } from "vitest";
import { isOldBacklog, RECENT_DAYS, REVIEW_RECENT_DAYS, type HomeWorkRow } from "./homeWork";
import { unansweredNow } from "./homeSummary";
import type { OperationsMetrics } from "./types";

const NOW = new Date("2026-10-26T03:00:00+09:00");

function row(over: Partial<HomeWorkRow>): HomeWorkRow {
  return { kind: "REVIEW", subject: "REVIEW", state: "NEEDS_LOOK", since: "2026-10-20", ...over } as HomeWorkRow;
}

function daysAgo(n: number): string {
  return new Date(NOW.getTime() - n * 24 * 60 * 60 * 1000).toISOString();
}

/**
 * Owner decisions: 2026-10-26 aged an undecided review out of 할 일 after two weeks; 2026-10-28 made it two weeks for
 * every kind — after a year of NAVER inquiries was read in, all eleven rows of 할 일 were 35–286 days old. A row
 * marked 「안전 관련 확인」 stays however old it is.
 */
describe("isOldBacklog — how long a row is this morning's work", () => {
  it("an undecided review ages out after two weeks", () => {
    expect(REVIEW_RECENT_DAYS).toBe(14);
    expect(RECENT_DAYS).toBe(14);
    expect(isOldBacklog(row({ since: daysAgo(13) }), NOW)).toBe(false);
    expect(isOldBacklog(row({ since: daysAgo(15) }), NOW)).toBe(true);
    expect(isOldBacklog(row({ since: "2026-07-30" }), NOW)).toBe(true);
  });

  it("a reply the seller started ages out the same way", () => {
    expect(isOldBacklog(row({ state: "DRAFT_NEEDED", since: daysAgo(13) }), NOW)).toBe(false);
    expect(isOldBacklog(row({ state: "DRAFT_NEEDED", since: daysAgo(90) }), NOW)).toBe(true);
    expect(isOldBacklog(row({ state: "AWAITING_APPROVAL", since: daysAgo(90) }), NOW)).toBe(true);
  });

  it("an inquiry and a case age out after two weeks too — not a year", () => {
    const inquiry = { kind: "INQUIRY", subject: "INQUIRY", state: "REPLY_NEEDED" } as const;
    expect(isOldBacklog(row({ ...inquiry, since: daysAgo(13) }), NOW)).toBe(false);
    expect(isOldBacklog(row({ ...inquiry, since: daysAgo(90) }), NOW)).toBe(true);
    const kase = { kind: "CASE", subject: "INQUIRY", state: "LOOK_INTO" } as const;
    expect(isOldBacklog(row({ ...kase, since: daysAgo(35) }), NOW)).toBe(true);
  });

  it("a possible safety report stays in 할 일 however old it is", () => {
    expect(isOldBacklog(row({ safety: true, since: daysAgo(200) }), NOW)).toBe(false);
    expect(isOldBacklog(row({ kind: "INQUIRY", subject: "INQUIRY", state: "REPLY_NEEDED", safety: true, since: daysAgo(400) }), NOW))
      .toBe(false);
  });

  it("a row with no date is never backlog", () => {
    expect(isOldBacklog(row({ since: null }), NOW)).toBe(false);
  });
});

function metrics(channels: Array<{ inquiryState: string }>): OperationsMetrics {
  return {
    kpis: [{ key: "unansweredInquiries", value: 0, excludedChannels: channels.length, freshnessUnproven: true }],
    channels,
  } as unknown as OperationsMetrics;
}

describe("unansweredNow — a lane that does not exist has no cell", () => {
  it("no channel collects inquiries (the upload-fed seller PC): NO_LANE, not 「수집 상태 확인 필요」", () => {
    expect(unansweredNow(metrics([{ inquiryState: "NOT_CONNECTED" }]))).toEqual({ kind: "NO_LANE" });
    expect(unansweredNow(metrics([{ inquiryState: "NOT_CONNECTED" }, { inquiryState: "NOT_SUPPORTED" }])))
      .toEqual({ kind: "NO_LANE" });
  });

  it("a channel that collects inquiries but is unproven still says so", () => {
    expect(unansweredNow(metrics([{ inquiryState: "NOT_CONNECTED" }, { inquiryState: "OBSERVED_FRESHNESS_UNPROVEN" }])))
      .toEqual({ kind: "UNQUALIFIED" });
  });
});
