import { describe, expect, it } from "vitest";
import { elapsedLabel, elapsedSource } from "./copy/customerOps";
import { caseWorkRow } from "./homeWork";
import type { CustomerOperationsDecisionRow } from "./customerOperationsTypes";

/**
 * <b>The elapsed-time contract</b> (product-owner decision, 2026-10-02).
 *
 * <p>The customer's own event time wherever there is one; {@code openedAt} only as a fallback; never a
 * second clock, never an estimate. The list and the detail of one item must therefore say the same thing,
 * which is what the defect was: 8일 대기 in the row and 9일 대기 in the pane beside it.
 */
const NOW = new Date("2026-10-02T09:00:00+09:00");

function decision(over: Partial<CustomerOperationsDecisionRow> = {}): CustomerOperationsDecisionRow {
  return {
    caseId: "c-1",
    subjectKind: "INQUIRY",
    channelNameKo: "카페24 자사몰",
    title: "문의 드립니다",
    preview: null,
    rating: null,
    reasonNote: "고객이 답변을 기다리고 있습니다.",
    summary: null,
    recommendedActionType: "REPLY_TO_CUSTOMER",
    recommendedAction: null,
    missingInformation: [],
    draftPrepared: true,
    decidedBy: "AGENT",
    openedAt: "2026-09-24T02:00:00Z",
    receivedOn: "2026-09-23",
    to: "/inquiries/i-1",
    ...over,
  };
}

describe("elapsed time — one source", () => {
  it("prefers the customer's receivedOn over openedAt", () => {
    // 09-23 (customer) and 09-24 (our record) are a day apart, and the customer's day is the answer.
    expect(elapsedSource("2026-09-23", "2026-09-24T02:00:00Z")).toBe("2026-09-23");
    expect(elapsedLabel(elapsedSource("2026-09-23", "2026-09-24T02:00:00Z"), "INQUIRY", NOW)).toBe("9일 대기");
  });

  it("falls back to openedAt when there is no customer timestamp", () => {
    expect(elapsedSource(null, "2026-09-24T02:00:00Z")).toBe("2026-09-24T02:00:00Z");
    /* 7일, not 8: an instant is counted in whole elapsed days (09-24 11:00 KST → 10-02 09:00 KST is 7d 22h)
       while a date-only value is counted in calendar days. That difference is `waitLabel`'s and is not
       touched here — what this contract fixes is WHICH value is read, not how a value is counted. */
    expect(elapsedLabel(elapsedSource(null, "2026-09-24T02:00:00Z"), "INQUIRY", NOW)).toBe("7일 대기");
  });

  /**
   * <b>1년을 넘으면 세는 것을 그만둔다</b> (문의 redesign, 2026-10-04 — product-owner decision).
   *
   * <p>데모 org의 Cafe24 백로그는 2016년까지 내려가고, 그 행이 들고 있던 것은 「3,727일 대기」였다 —
   * 산수이지 정보가 아니다. 1년이 넘은 문의에 남은 질문은 「언제 들어온 것인가」뿐이라, 그 자리는 접수일
   * 자체가 가진다. 자리를 비우지는 않는다: 비우면 오래된 문의만 시간을 말하지 않는 목록이 된다.
   */
  it("past a year the wait is the day it arrived, not a count", () => {
    expect(elapsedLabel("2016-09-09", "INQUIRY", NOW)).toBe("2016-09-09 접수");
    // An instant is read in the seller's own calendar, so the label is the KST day it landed on.
    expect(elapsedLabel("2016-07-20T22:00:00Z", "INQUIRY", NOW)).toBe("2016-07-21 접수");
    // The boundary is 365 days of waiting: a year old still counts, a year and a day is dated.
    expect(elapsedLabel("2025-10-02", "INQUIRY", NOW)).toBe("365일 대기");
    expect(elapsedLabel("2025-10-01", "INQUIRY", NOW)).toBe("2025-10-01 접수");
    // 리뷰는 기다리는 것이 아니라 일어난 것이라, 이 규칙은 리뷰의 단어에 닿지 않는다.
    expect(elapsedLabel("2016-09-09", "REVIEW", NOW)).toMatch(/일 전$/);
  });

  it("says nothing rather than inventing a time when it has neither", () => {
    expect(elapsedSource(null, null)).toBeNull();
    expect(elapsedLabel(elapsedSource(null, null), "INQUIRY", NOW)).toBeNull();
  });

  it("a review is never 대기 — it is N일 전", () => {
    expect(elapsedLabel("2026-09-23", "REVIEW", NOW)).toBe("9일 전");
    expect(elapsedLabel("2026-09-23", "REVIEW", NOW)).not.toMatch(/대기/);
    // And the axis is what the item is ABOUT: a case opened over a review is still review work.
    const row = caseWorkRow(decision({ subjectKind: "REVIEW", receivedOn: "2026-09-23" }));
    expect(elapsedLabel(row.since, row.subject, NOW)).toBe("9일 전");
  });

  it("the list row and the detail resolve the same source for one item", () => {
    const row = decision();
    const listSource = caseWorkRow(row).since;
    // The pane is handed the case detail, whose two fields are the same two facts under the same names.
    const paneSource = elapsedSource(row.receivedOn, row.openedAt);
    expect(listSource).toBe(paneSource);
    expect(elapsedLabel(listSource, "INQUIRY", NOW)).toBe(elapsedLabel(paneSource, "INQUIRY", NOW));
  });

  it("…and still the same string when only the fallback exists", () => {
    const row = decision({ receivedOn: null });
    expect(caseWorkRow(row).since).toBe(elapsedSource(row.receivedOn, row.openedAt));
    expect(elapsedLabel(caseWorkRow(row).since, "INQUIRY", NOW)).toBe("7일 대기");
  });
});
