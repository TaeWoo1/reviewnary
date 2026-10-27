import { describe, expect, it } from "vitest";
import { mergeHomeWork, workBucket } from "./homeWork";
import { WORK_STATE } from "./workState";
import type { OperationsHome, OperatorVocItem, ReviewWorkView } from "./types";
import type { CustomerOperationsHome } from "./customerOperationsTypes";

/**
 * <b>확인할 일 holds every piece of review work</b> (UI/UX v2 Phase 3). The audit found two holes: the list read
 * the Home's three-row slice of undecided 확인 필요 reviews (11 existed, 3 were listed, nothing said so), and the
 * seller's own reply work before approval — 초안 필요 / 승인 대기 — was reachable only from the 리뷰 screen's
 * 「내 답변 작업」. These pin the composer that closes both, without re-deciding anything.
 */
const NOW = new Date("2026-09-22T03:00:00Z");

function attention(id: string) {
  return { reviewId: id, accountId: null, channelCode: "NAVER", rating: 1, occurredOn: "2026-09-01", productName: "상품", quote: `불만 ${id}` };
}

function ops(rows: ReturnType<typeof attention>[]): OperationsHome {
  return {
    reviews: { needsAttentionUndecided: 11, needsAttentionTotal: 14, watchTotal: 0, rows },
    problems: { decidable: 0, observing: 0, dormant: 0, rows: [] },
    collection: [],
    prepared: { reviewRepliesApproved: 0, inquiryDraftsReady: 0, improvementDraftsReady: 0, rows: [] },
  } as unknown as OperationsHome;
}

function todo(reviewId: string, state: "DRAFT_NEEDED" | "AWAITING_APPROVAL"): OperatorVocItem {
  return {
    channelCode: "NAVER", channelNameKo: "네이버 스마트스토어", sourceType: "REVIEW", productName: "선바로", rating: 4,
    replyStatus: "PENDING", sourceCreatedDate: "2026-08-28", collectedDate: "2026-08-29", signalType: "LOW_RATING_REVIEW",
    safePreview: `답변할 리뷰 ${reviewId}`, actionRef: `review:${reviewId}`, reviewId, triageDisposition: "RESPONSE_NEEDED",
    hasReplyPreparation: state === "AWAITING_APPROVAL", replyWorkState: state, category: null, hasReportedSubmission: false,
  } as OperatorVocItem;
}

function reviewWork(attentionRows: ReturnType<typeof attention>[], total: number, items: OperatorVocItem[]): ReviewWorkView {
  return {
    attentionTotal: total,
    attention: attentionRows,
    committed: [{ accountId: "acc-nv", channelCode: "NAVER", channelNameKo: "네이버", coverage: "COVERED", todo: items, recentlyReported: [] }],
  } as unknown as ReviewWorkView;
}

describe("mergeHomeWork — the review half, whole", () => {
  it("carries 발생 or 우려 for each safety row, from whichever read made it (owner decision 2026-10-23)", () => {
    const worried = { ...attention("a-worry"), safety: true, safetyReading: "WORRY" as const };
    const older = { ...attention("a-old"), safety: true };
    const work = mergeHomeWork(null, null, null, NOW, {
      ...reviewWork([worried, older], 2, [todo("r-burn", "AWAITING_APPROVAL"), todo("r-fear", "DRAFT_NEEDED")]),
      safetyReviewIds: ["r-burn", "r-fear"],
      safetyWorryReviewIds: ["r-fear"],
    });
    const byId = Object.fromEntries(work.rows.map((r) => [r.subjectId, r]));
    expect(byId["a-worry"].safetyReading).toBe("WORRY");
    expect(byId["a-old"].safetyReading, "an older server's row reads as the stronger mark").toBeNull();
    expect(byId["r-burn"].safetyReading).toBe("OCCURRENCE");
    expect(byId["r-fear"].safetyReading).toBe("WORRY");
  });

  it("says where a reply that came back from the delegate stands, still under 승인할 일 (measured 2026-10-24)", () => {
    const work = mergeHomeWork(null, null, null, NOW, {
      ...reviewWork([], 0, [todo("r-held", "AWAITING_APPROVAL"), todo("r-failed", "AWAITING_APPROVAL"),
        todo("r-phone", "AWAITING_APPROVAL"), todo("r-here", "AWAITING_APPROVAL")]),
      delegatedStates: { "r-held": "HELD", "r-failed": "FAILED", "r-phone": "AWAITING_APPROVAL", "r-gone": "POSTED" },
    });
    const byId = Object.fromEntries(work.rows.map((r) => [r.subjectId, r]));
    expect(WORK_STATE[byId["r-held"].state].text).toBe("고칠 곳");
    expect(WORK_STATE[byId["r-failed"].state].text).toBe("등록 못 함");
    // Waiting on the phone is every draft now (2026-10-25): plain 승인 대기, not a lane word.
    expect(WORK_STATE[byId["r-phone"].state].text).toBe("확정 전");
    expect(WORK_STATE[byId["r-here"].state].text, "no lane state: the draft read stands").toBe("확정 전");
    expect(work.rows.map(workBucket)).toEqual(["approve", "approve", "approve", "approve"]);
  });

  it("lists every undecided 확인 필요 review, not the Home's three", () => {
    const eleven = Array.from({ length: 11 }, (_, i) => attention(`a${i}`));
    const work = mergeHomeWork(null, ops(eleven.slice(0, 3)), null, NOW, reviewWork(eleven, 11, []));
    expect(work.rows.filter((r) => r.reason.tag === "리뷰")).toHaveLength(11);
    expect(work.truncated).toBe(false);
  });

  it("says the list is deeper than the read when the server holds more than it returned", () => {
    const work = mergeHomeWork(null, null, null, NOW, reviewWork([attention("a1")], 150, []));
    expect(work.truncated).toBe(true);
  });

  it("carries the seller's own reply work before approval, in the workState words, opening the Review Case", () => {
    const work = mergeHomeWork(null, null, null, NOW,
      reviewWork([], 0, [todo("r-draft", "DRAFT_NEEDED"), todo("r-approve", "AWAITING_APPROVAL")]));
    const byId = Object.fromEntries(work.rows.map((r) => [r.subjectId, r]));
    expect(byId["r-draft"].reason.tag).toBe("초안 필요");
    expect(byId["r-approve"].reason.tag).toBe("확정 전");
    /*
      <b>Re-pointed to where 「미발송」 now stands</b> (product-owner decision, 2026-10-01: the row's
      second line is customer context only). The guarantee has not moved — a prepared reply must not
      read as a sent one — but the fact is the STATE's, not the line's: `AWAITING_APPROVAL` is the
      row's lead word and `WORK_STATE` words it 「승인 대기」, with 미발송 said once on the surfaces that
      own the draft. What the line may no longer do is restate it.
    */
    expect(byId["r-approve"].state).toBe("AWAITING_APPROVAL");
    expect(byId["r-approve"].line).not.toContain("미발송");
    expect(byId["r-approve"].line).toBe("선바로");
    expect(byId["r-draft"].to).toBe("/reviews/reply/r-draft");
    expect(byId["r-draft"].kind).toBe("REVIEW");
  });

  /**
   * <b>The second line is the customer's words, and only when they are new words</b> (product-owner
   * decision, 2026-10-01). Measured at 1600×1000: one live row drew 「케이블 한 줄 2.5 3c 폭 10mm…」 as
   * both its title and its line — the channel's own question cut at two lengths, which is two strings
   * and one sentence.
   */
  it("draws the inquiry's own body, and drops it when it is the title again", () => {
    const q = (over: Record<string, unknown>) => ({
      content: [{
        workItemId: "w", inquiryId: "i", sellerAccountId: "a", channelId: "c", channelCode: "CAFE24",
        channelNameKo: "카페24", productId: null, productName: null, phase: "OPEN", status: "UNANSWERED",
        receivedAt: "2026-09-20T00:00:00Z", hasDraft: false, ...over,
      }],
      page: 0, size: 50, totalElements: 1, totalPages: 1,
    } as never);

    // New words: the question's body, which the Home never drew before this.
    expect(mergeHomeWork(null, null, q({ title: "환불 문의", snippet: "주문 취소 가능할까요?" }), NOW).rows[0].line)
      .toBe("주문 취소 가능할까요?");
    // <b>No title at all</b> — a NAVER product inquiry. `title` falls back to the snippet, so the
    // comparison has to be against the title the ROW draws, not against the empty field. This is the
    // live row that survived the first version of this guard.
    expect(mergeHomeWork(null, null, q({ title: null, snippet: "케이블 한 줄 2.5 3c 폭 10mm…" }), NOW).rows[0].line).toBeNull();
    expect(mergeHomeWork(null, null, q({ title: null, snippet: "케이블 한 줄 2.5 3c 폭 10mm…" }), NOW).rows[0].title)
      .toBe("케이블 한 줄 2.5 3c 폭 10mm…");
    // The same sentence cut at two lengths — a prefix either way adds nothing to the row.
    expect(mergeHomeWork(null, null, q({ title: "케이블 한 줄 2.5 3c", snippet: "케이블 한 줄 2.5 3c 폭 10mm" }), NOW).rows[0].line)
      .toBeNull();
    expect(mergeHomeWork(null, null, q({ title: "케이블 한 줄 2.5 3c 폭 10mm", snippet: "케이블 한 줄 2.5 3c" }), NOW).rows[0].line)
      .toBeNull();
    // And the state is never restated there — that is the lead badge's, off `hasDraft`.
    const row = mergeHomeWork(null, null, q({ title: "환불 문의", snippet: "주문 취소 가능할까요?" }), NOW).rows[0];
    expect(row.state).toBe("REPLY_NEEDED");
    expect(row.line).not.toContain("초안");
  });

  it("drops a case's summary when it is the title again — the title falls back to it", () => {
    // A subject with no title of its own: `title` becomes the summary, so printing the summary in the
    // line would draw one sentence as both lines. Measured live at 1600×1000.
    const co = (over: Record<string, unknown>) => ({
      decisions: { total: 1, rows: [{
        caseId: "c-9", subjectKind: "INQUIRY", channelNameKo: "네이버", rating: null, reasonNote: "",
        recommendedActionType: "REPLY_TO_CUSTOMER", recommendedAction: null, missingInformation: [],
        draftPrepared: false, decidedBy: "RULE", openedAt: "2026-09-20T00:00:00Z", to: "/inquiries/i-9",
        ...over,
      }] },
      handled: { rows: [] },
    } as unknown as CustomerOperationsHome);

    expect(mergeHomeWork(co({ title: null, summary: "케이블 한 줄 2.5 3c 폭 10mm" }), null, null, NOW).rows[0].line).toBeNull();
    expect(mergeHomeWork(co({ title: "9oz 뚜껑도 파나요?", summary: "등록된 지식으로 답변할 수 있어요." }), null, null, NOW).rows[0].line)
      .toBe("등록된 지식으로 답변할 수 있어요.");
  });

  it("a case about the same review wins — one review is one row", () => {
    const co = {
      decisions: { total: 1, rows: [{
        caseId: "c-1", subjectKind: "REVIEW", channelNameKo: "네이버", title: "케이스", rating: 2, reasonNote: "",
        summary: null, recommendedActionType: null, recommendedAction: null, missingInformation: [], draftPrepared: false,
        decidedBy: "RULE", openedAt: "2026-09-01T00:00:00Z", to: "/reviews/reply/r-draft",
      }] },
      handled: { rows: [] },
    } as unknown as CustomerOperationsHome;
    const work = mergeHomeWork(co, null, null, NOW, reviewWork([], 0, [todo("r-draft", "DRAFT_NEEDED")]));
    expect(work.rows).toHaveLength(1);
    expect(work.rows[0].kind).toBe("CASE");
  });

  it("without the read, the list is what it was — the Home's own rows", () => {
    const work = mergeHomeWork(null, ops([attention("a1"), attention("a2"), attention("a3")]), null, NOW);
    expect(work.rows).toHaveLength(3);
  });
});
