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
