// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from "vitest";
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import type { CustomerOperationsHome } from "../../lib/customerOperationsTypes";
import type { InquiryQueueResponse, OperationsHome } from "../../lib/types";
import { expectNoAxeViolations } from "../../test/axe";

const api = vi.hoisted(() => ({
  getInquiryQueueStrict: vi.fn(),
  activateCustomerOperations: vi.fn(),
  resumeCustomerOperations: vi.fn(),
}));
vi.mock("../../lib/apiClient", () => ({ api, getToken: () => null }));

import { CustomerOpsHome, HOME_ROWS, HOME_ROWS_NARROW, coHomeApplies, visibleHomeRows } from "./CustomerOpsHome";
import { mergeHomeWork } from "../../lib/homeWork";
import { waitLabel } from "../../lib/copy/customerOps";

const NOW = new Date("2026-09-16T05:30:00Z"); // 14:30 KST

function co(over: Partial<CustomerOperationsHome> = {}): CustomerOperationsHome {
  return {
    available: true,
    eligible: true,
    status: "ACTIVE",
    cadenceMinutes: 120,
    lastCheckedAt: "2026-09-16T05:02:00Z",
    lastRunStatus: "SUCCESS",
    nextCheckAt: "2026-09-16T07:00:00Z",
    sources: [],
    decisions: {
      total: 2,
      rows: [
        {
          caseId: "c-1", subjectKind: "INQUIRY", channelNameKo: "카페24", title: "뚜껑이 깨져서 왔어요", rating: null, preview: null,
          reasonNote: "고객이 답변을 기다립니다.", summary: "사진에서 균열이 보입니다.", recommendedActionType: "CANCEL_OR_EXCHANGE",
          recommendedAction: null, missingInformation: [], draftPrepared: true, decidedBy: "AGENT",
          openedAt: "2026-09-16T02:30:00Z",
          receivedOn: null, to: "/inquiries/i-1",
        },
        {
          caseId: "c-2", subjectKind: "INQUIRY", channelNameKo: "네이버 스마트스토어", title: "9oz 뚜껑도 파나요?", rating: null, preview: null,
          reasonNote: "고객이 답변을 기다립니다.", summary: null, recommendedActionType: "ADD_KNOWLEDGE",
          recommendedAction: null, missingInformation: ["9oz 뚜껑 판매 여부"], draftPrepared: false, decidedBy: "AGENT",
          openedAt: "2026-09-16T00:30:00Z",
          receivedOn: null, to: "/inquiries/i-2",
        },
      ],
    },
    handled: {
      since: null, autoResolved: 31, monitoring: 4, draftsPrepared: 9, verifying: 0, checked: 47,
      rows: [
        { caseId: "h-1", subjectKind: "REVIEW", channelNameKo: "네이버", title: "좋아요", rating: 5, disposition: "AUTO_RESOLVED", decidedBy: "RULE", reasonNote: "", summary: null, verifying: false, to: "/reviews/reply/r-settled" },
      ],
    },
    gaps: { total: 0, rows: [] },
    ...over,
  };
}

function ops(): OperationsHome {
  return {
    reviews: {
      needsAttentionUndecided: 2, needsAttentionTotal: 2, watchTotal: 0,
      rows: [
        { reviewId: "r-1", accountId: "a", channelCode: "NAVER", rating: 1, occurredOn: "2026-09-15", productName: "컵 뚜껑 12oz", quote: "뚜껑이 컵에 잘 안 맞아요" },
        { reviewId: "r-settled", accountId: "a", channelCode: "NAVER", rating: 5, occurredOn: "2026-09-10", productName: null, quote: "좋아요" },
      ],
    },
    problems: {
      decidable: 1, observing: 1,
      rows: [
        {
          issue: {
            id: "iss-1", title: "포장 파손", severity: "HIGH", lifecycleState: "ACTING", lifecycleLabelKo: "조치 중",
            change: { kinds: ["SURGE"], labelsKo: ["급증"], highSurge: true, surgeWindowCount: 4, surgeBaselineWeekly: 1 },
          },
          context: {
            issueId: "iss-1", aspect: "포장",
            evidence: {
              totalEvidence: 9, unattributedEvidence: 0,
              byProduct: [
                { productId: "p-1", productName: "컵 뚜껑 12oz", evidenceCount: 9, productReviews: 120, firstOccurredOn: "2026-08-01", lastOccurredOn: "2026-09-18" },
              ],
            },
          },
        },
        // No trend fired on this one — the ordinary case for a problem that repeated steadily rather than
        // suddenly, and the one the old single line could not name at all.
        {
          issue: {
            id: "iss-2", title: "접착 부족", severity: "NORMAL", lifecycleState: "OBSERVING", lifecycleLabelKo: "관찰 중",
            change: { kinds: [], labelsKo: [], highSurge: false, surgeWindowCount: 0, surgeBaselineWeekly: 0 },
          },
          context: {
            issueId: "iss-2", aspect: "접착",
            evidence: {
              totalEvidence: 16, unattributedEvidence: 0,
              byProduct: [
                { productId: "p-2", productName: "선바로 전선몰딩", evidenceCount: 16, productReviews: 1761, firstOccurredOn: "2025-07-29", lastOccurredOn: "2026-08-19" },
              ],
            },
          },
        },
      ],
    },
    collection: [],
    prepared: {
      reviewRepliesApproved: 2, inquiryDraftsReady: 1, improvementDraftsReady: 0,
      rows: [
        // Approved and unposted — decided, so it can never reach 확인 필요 above.
        { kind: "REVIEW_REPLY", id: "rev-approved", label: "승인된 리뷰 답변", detail: "컵 뚜껑 12oz · 2026-09-05", channelCode: "NAVER", to: "/reviews/reply/rev-approved" },
        { kind: "REVIEW_REPLY", id: "rev-approved-2", label: "승인된 리뷰 답변", detail: "선바로 전선몰딩 · 2026-09-05", channelCode: "NAVER", to: "/reviews/reply/rev-approved-2" },
        // A draft-ready inquiry — AWAITING_SELLER, so the work queue above is ALREADY showing it (w-1/i-1).
        { kind: "INQUIRY_REPLY", id: "w-1", label: "초안이 준비된 문의", detail: "뚜껑이 깨져서 왔어요", channelCode: null, to: "/inquiries/i-1" },
      ],
    },
  } as never;
}

function queue(over: Partial<InquiryQueueResponse> = {}): InquiryQueueResponse {
  return {
    content: [
      { workItemId: "w-1", inquiryId: "i-1", sellerAccountId: "a", channelId: "ch", channelCode: "CAFE24", channelNameKo: "카페24", productId: null, productName: null, phase: "PROPOSED", status: "UNANSWERED", title: "뚜껑이 깨져서 왔어요", snippet: null, receivedAt: "2026-09-16T02:00:00Z", hasDraft: true },
      { workItemId: "w-3", inquiryId: "i-3", sellerAccountId: "a", channelId: "ch", channelCode: "CAFE24", channelNameKo: "카페24", productId: null, productName: null, phase: "OPEN", status: "UNANSWERED", title: null, snippet: "주문 취소 가능할까요?", receivedAt: "2026-09-16T04:30:00Z", hasDraft: false },
    ],
    page: 0, size: 50, totalElements: 2, totalPages: 1,
    ...over,
  };
}

function draw(home = co(), operations: OperationsHome | null = ops()) {
  const onChanged = vi.fn();
  const view = render(
    <MemoryRouter>
      <CustomerOpsHome co={home} ops={operations} now={NOW} onChanged={onChanged} />
    </MemoryRouter>,
  );
  return { ...view, onChanged };
}

describe("mergeHomeWork — one list from three reads", () => {
  it("is keyed by the screen that owns the item: a case wins, and nothing a case already settled comes back as work", () => {
    const work = mergeHomeWork(co(), ops(), queue());
    const keys = work.rows.map((r) => r.key);
    expect([...keys].sort()).toEqual(["case:c-1", "case:c-2", "inquiry:i-3", "review:r-1"]);
    // i-1 is the case c-1; r-settled was settled by the job.
    expect(keys).not.toContain("inquiry:i-1");
    expect(keys).not.toContain("review:r-settled");
    expect(new Set(keys).size).toBe(keys.length);
  });

  /**
   * <b>Re-pointed: there is no single age key across the lanes any more</b> (product-owner decision,
   * 2026-10-01). This asserted 「oldest first」 over the merged list, and that comparator is exactly
   * what the Home's visual QA failed on: it inverted the review lane's declared order (the server
   * serves undecided 확인 필요 reviews `receivedAt desc`) and ranked a review's authoring date against
   * an inquiry's receipt time, so four네이버 reviews written 212–339 days ago filled the brief.
   *
   * <p>The surviving guarantee is that the ORDER IS A LANE'S OWN, not this list's — and the one lane
   * whose order has to be restated here is the inquiry lane, because its read arrives
   * `createdAt desc` while `/inquiries` reads it oldest-first.
   */
  it("concatenates the lanes, each in its own order — case, review, inquiry", () => {
    const work = mergeHomeWork(co(), ops(), queue());
    expect(work.rows.map((r) => r.key)).toEqual(["case:c-1", "case:c-2", "review:r-1", "inquiry:i-3"]);
  });

  it("orders the inquiry lane oldest-first — the SLA reading, restated because the read is newest-first", () => {
    const older = {
      workItemId: "w-8", inquiryId: "i-older", sellerAccountId: "a", channelId: "ch", channelCode: "CAFE24",
      channelNameKo: "카페24", productId: null, productName: null, phase: "OPEN" as const,
      status: "UNANSWERED" as const, title: "배송 언제 오나요", snippet: null,
      receivedAt: "2026-09-10T00:00:00Z", hasDraft: false,
    };
    // Handed in newest-first, which is how `InquiryQueueService` sorts it (`createdAt desc`).
    const work = mergeHomeWork(null, null, queue({ content: [...queue().content, older] }), NOW);
    const inquiries = work.rows.filter((r) => r.kind === "INQUIRY").map((r) => r.key);
    expect(inquiries).toEqual(["inquiry:i-older", "inquiry:i-1", "inquiry:i-3"]);
  });

  it("a read that failed is absent, not zero — and the count is what was drawn", () => {
    const work = mergeHomeWork(co(), ops(), null);
    expect(work.rows.map((r) => r.key)).toEqual(["case:c-1", "case:c-2", "review:r-1"]);
    expect(work.truncated).toBe(false);
  });

  /**
   * <b>Longest-waiting-first, applied INSIDE two groups rather than across them.</b> Measured on the demo org, the
   * five rows this Home briefs were five Cafe24 questions from 2016 — 「3838일 대기」 — while the case Reviewnary
   * investigated last night, the three reviews it flagged and the inquiries from this month all sat under 「+22」.
   * The predicate is `/inquiries`'s own {@code isOldBacklog}, imported rather than restated, so the two lists cannot
   * disagree about which rows are this morning's work.
   *
   * <p>Nothing is hidden or dropped: the old rows keep their place in the same single list, after the recent ones.
   *
   * <p><b>The year boundary survived the ordering change</b> (2026-10-01). What went is the comparator
   * applied ACROSS the lanes; this split is `/inquiries`' own predicate, it is drawn as a divider
   * rather than a sort, and it still decides which group a row stands in.
   */
  it("한 해 넘게 묵은 백로그는 뒤로 간다 — 숨기지 않고, 오늘 일 뒤에", () => {
    const old2016 = {
      workItemId: "w-9", inquiryId: "i-2016", sellerAccountId: "a", channelId: "ch", channelCode: "CAFE24",
      channelNameKo: "카페24", productId: null, productName: null, phase: "OPEN" as const,
      status: "UNANSWERED" as const, title: "현금영수증해주세요", snippet: null,
      receivedAt: "2016-03-19T04:38:27Z", hasDraft: false,
    };
    const work = mergeHomeWork(co(), ops(), queue({ content: [...queue().content, old2016] }), NOW);
    const keys = work.rows.map((r) => r.key);
    // It is still there, and it is last — the row that waited longest of all.
    expect(keys).toContain("inquiry:i-2016");
    expect(keys[keys.length - 1]).toBe("inquiry:i-2016");
    // And this morning's work still leads, each lane in its own order.
    expect(keys.slice(0, 4)).toEqual(["case:c-1", "case:c-2", "review:r-1", "inquiry:i-3"]);
  });

  it("says when a read knows of more than it returned", () => {
    expect(mergeHomeWork(co(), null, queue({ totalElements: 80 })).truncated).toBe(true);
    const more = co();
    expect(mergeHomeWork({ ...more, decisions: { ...more.decisions, total: 9 } }, null, null).truncated).toBe(true);
  });
});

describe("CustomerOpsHome", () => {
  beforeEach(() => {
    Object.values(api).forEach((fn) => fn.mockReset());
    api.getInquiryQueueStrict.mockResolvedValue(queue());
  });

  it("opens on the list, with what was checked on one quiet line above it", async () => {
    const { container } = draw();
    // <b>The counter band is gone</b> (reference-based hierarchy v1): neither Linear's Triage list nor
    // Intercom's Inbox puts a summary card over a work list. Every fact it carried is on the status line.
    const status = await screen.findByTestId("today-status");
    // <b>Three facts, and no fourth</b> (product-owner decision, 2026-09-26). Which today it is, whether the job
    // is running, and what the seller already decided and has not finished. Everything else this line used to
    // carry is a parameter of the job or a tally of what it did, and both live on the screen that owns the job —
    // asserted there, in `CustomerOperations.test.tsx`, so neither can be lost by being moved.
    expect(status).toHaveTextContent("자동 확인 중");
    expect(status).not.toHaveTextContent("최근 24시간 자동 확인");
    expect(status).not.toHaveTextContent("47건");
    expect(status).not.toHaveTextContent("정리 31");
    expect(status).not.toHaveTextContent("초안 9");
    expect(status).not.toHaveTextContent("다음 확인");
    expect(status).not.toHaveTextContent("주기");
    // 실행 대기 is an obligation and stands as its own cell in the band (product-owner decision,
    // 2026-09-30) — the seller already decided it and it is not finished. It is never a pill, and it is
    // never on the date line, which answers 「자동 확인이 돌고 있나」 and nothing else.
    const summary = await screen.findByTestId("today-summary");
    expect(within(summary).getByRole("link", { name: /실행 대기/ })).toHaveAttribute("href", "#실행-대기");
    expect(within(status).queryByRole("link", { name: /실행 대기/ })).toBeNull();
    // A dot and a word, not a filled capsule: nothing on this line has both a pill radius and pill padding.
    expect(status.querySelectorAll("[class*='rounded-full'][class*='px-']")).toHaveLength(0);
    // 반복 문제 is a pattern, not a customer waiting: not up here, and not hidden either.
    expect(within(status).queryByRole("link", { name: /반복 문제/ })).toBeNull();
    // <b>The same guarantee, on the control that now carries it</b> (Home visual target, 2026-10-01).
    // 반복 문제 is a card with a three-slot header — name, note, way out — so its exit says 「전체 보기 →」
    // like every other section's rather than repeating the section name inside the link. What is asserted
    // is unchanged: the Home offers a way to the whole set, and it goes to `/memory`.
    expect(
      within(screen.getByRole("region", { name: "반복 문제" })).getByRole("link", { name: /전체 보기/ }),
    ).toHaveAttribute("href", "/memory");
    // <b>The count moved up one line</b> (product-owner decision, 2026-09-26): it is 「지금 볼 것」 of the
    // summary, and the heading no longer repeats it 12px below. Same number, same `work`, one place.
    await waitFor(() => expect(summary).toHaveTextContent("확인할 일 4"));
    // The brief's heading is 「오늘 먼저 볼 일」 since the Home visual target: 확인할 일 names the QUEUE —
    // the rail item, the screen it links to, and the summary cell above — and printing it here was the
    // third copy. What is asserted is unchanged and is the point of the re-point: the count lives in the
    // band, once, and the heading of the rows does not repeat it.
    const heading = await screen.findByRole("heading", { name: /오늘 먼저 볼 일/ });
    expect(heading).not.toHaveTextContent("4");
    // <b>The breakdown is the queue's, not the brief's</b> (product-owner decision, 2026-10-01). It used to
    // stand here: 「교환·환불 1 · 정보 부족 1 · 답변 필요 1 · 리뷰 1」, four tallies over a list showing four
    // rows. Every one of them is a filter chip on 확인할 일, where it is a control the seller can press; on a
    // brief it is a number nothing can be done with. The ORDER stays, because the order is how to read the
    // rows that are here.
    // The order, in the sentence the target writes it as rather than as the two-syllable chip 「오래된
    // 순」 — same fact, and it is still the brief's note slot, beside the heading.
    /*
      <b>Re-pointed: the note describes a selection, not a ranking</b> (product-owner decision,
      2026-10-01). It asserted 「오래 기다린 … 부터」, which was the single age comparator this brief was
      sliced from and which the lane quota replaced. The guarantee is that the heading SAYS WHAT THE
      FOUR ROWS ARE — a seller must not have to guess why these four — and that is asserted on the
      sentence the rows can prove.
    */
    expect(heading.parentElement).toHaveTextContent(/문의와 리뷰에서 먼저 볼 것/);
    expect(heading.parentElement).not.toHaveTextContent(/오래 기다린 .*부터/);
    expect(heading.parentElement).not.toHaveTextContent(/교환·환불 1|정보 부족 1/);
    await expectNoAxeViolations(container);
  });

  /**
   * <b>Re-pointed to the brief's new composition</b> (product-owner decision, 2026-10-01). Every
   * guarantee below is the one that was here — a review carries the way back, each row keeps the word
   * that tells it from its neighbours, a prepared draft never reads as a sent one, and no row carries
   * a solid CTA. What moved is WHICH rows are drawn and WHERE two of those facts stand:
   *
   * <ul>
   *   <li>The four rows are no longer the first four of one age-sorted list — they are
   *       {@code homeBriefRows}' lane quota (Review 2 / Inquiry 1 / Case 1), so the rows are looked
   *       up by what they say rather than by index.</li>
   *   <li>「초안 있음 · 미발송」 was in the row's second line, which is customer context only now. The
   *       fact is the state word's, off the same {@code draftPrepared} field.</li>
   *   <li>A review says 「N일 전」, not 「N일 대기」: nobody is waiting on a review. An inquiry and a
   *       case still say 대기, which is the claim they can make.</li>
   * </ul>
   */
  it("each row says why, from where, how long — and no row carries a button of its own", async () => {
    draw();
    const list = await screen.findByRole("list", { name: "확인할 일" });
    // jsdom cannot measure a width, so the brief draws its narrow count.
    await waitFor(() => expect(within(list).getAllByRole("link")).toHaveLength(visibleHomeRows(false)));

    const review = within(list).getByRole("link", { name: /뚜껑이 컵에 잘 안 맞아요/ });
    // A review carries the way back to the work list it was opened from.
    expect(review).toHaveAttribute("href", "/reviews/reply/r-1?from=work");
    expect(review).toHaveTextContent("리뷰");
    expect(review).toHaveTextContent("네이버 리뷰");
    expect(review).toHaveTextContent("★1");
    // <b>대기 is a claim, and it is not true of a review.</b> The customer wrote it a day ago; nobody
    // has been waiting on it.
    expect(review).toHaveTextContent("1일 전");
    expect(review).not.toHaveTextContent("대기");

    const kase = within(list).getByRole("link", { name: /뚜껑이 깨져서 왔어요/ });
    expect(kase).toHaveAttribute("href", "/customer-operations/cases/c-1");
    // The state word leads, and it is where 「a draft exists and has not been sent」 is said — once.
    expect(kase).toHaveTextContent("초안 준비됨");
    expect(kase).not.toHaveTextContent("초안 있음 · 미발송");
    // The second line is the case's own account of the subject — customer context, not our workflow.
    expect(kase).toHaveTextContent("사진에서 균열이 보입니다.");
    // A case IS waiting: reviewnary opened it and the customer has had no answer.
    expect(kase).toHaveTextContent("3시간 대기");
    // The verb (「검토」, 「정보 입력」) is gone from the row: the one primary action lives in the item itself.
    expect(kase).not.toHaveTextContent("정보 입력");

    // The inquiry lane's row carries the customer's own words in the second line — the slot that used
    // to read 「초안 없음」, which the badge beside it already said.
    const inquiry = within(list).getByRole("link", { name: /주문 취소 가능할까요/ });
    expect(inquiry).toHaveAttribute("href", "/inquiries/i-3");
    expect(inquiry).not.toHaveTextContent("초안 없음");

    // No row carries a solid CTA. This used to assert 「no `.bg-brand-700` anywhere」, which became wrong
    // when the row grew a state word: `Status` draws an `info` state's 6px dot in the accent, and a dot
    // is not a control. The claim is about a BUTTON, so it is asserted as one.
    expect(list.querySelectorAll(".bg-brand-700.text-white")).toHaveLength(0);
    expect(list).not.toHaveTextContent("검토");
  });

  /**
   * <b>Four slots, allotted by lane</b> (product-owner decision, 2026-10-01 — Review 2 / Inquiry 1 /
   * Case 1). The regression this catches is the measured one: taking the first N of one order made
   * the brief whichever lane happened to be longest, and on the live org that was four reviews with
   * this month's inquiry nowhere on the screen.
   */
  it("briefs across the lanes rather than filling up from the longest one", async () => {
    draw();
    const list = await screen.findByRole("list", { name: "확인할 일" });
    await waitFor(() => expect(within(list).getAllByRole("link")).toHaveLength(visibleHomeRows(false)));
    // Three slots, three lanes — not three of whichever lane came first in the merged order.
    expect(within(list).getByRole("link", { name: /뚜껑이 컵에 잘 안 맞아요/ })).toBeInTheDocument();
    expect(within(list).getByRole("link", { name: /주문 취소 가능할까요/ })).toBeInTheDocument();
    expect(within(list).getByRole("link", { name: /뚜껑이 깨져서 왔어요/ })).toBeInTheDocument();
  });

  it("a source that was not read is excluded from the count and says so, with its fix", async () => {
    draw(
      co({
        gaps: { total: 1, rows: [{ caseId: "g-1", channelCode: "COUPANG", channelNameKo: "쿠팡", reason: "SOURCE_AUTH_REQUIRED", dataTypes: ["INQUIRY"], since: "2026-09-15T13:00:00Z", lastSeenAt: "", to: "/connect/coupang" }] },
        sources: [
          { channelCode: "NAVER", channelNameKo: "네이버", dataType: "INQUIRY", completeness: "PARTIAL", observedCount: 3, newCount: 1, failureReason: "TIMEOUT", observedAt: null, sellerActionRequired: false },
        ],
      }),
    );
    const gaps = await screen.findByLabelText("집계에서 빠진 곳");
    expect(gaps).toHaveTextContent("쿠팡 문의 연결 만료 · 어제 22:00부터 집계 제외");
    expect(within(gaps).getByRole("link", { name: "재연결" })).toHaveAttribute("href", "/connect/coupang");
    expect(gaps).toHaveTextContent("네이버 문의 일부만 확인 · 응답 지연");
  });

  it("a list read that failed counts what loaded and says the count is partial — never 「N+」", async () => {
    api.getInquiryQueueStrict.mockRejectedValue(new Error("down"));
    draw();
    const gaps = await screen.findByLabelText("집계에서 빠진 곳");
    await waitFor(() => expect(gaps).toHaveTextContent("문의 목록 읽기 실패 · 부분 집계"));
    expect(gaps).not.toHaveTextContent("리뷰 목록 읽기 실패");
    // c-1, c-2 and the review — what loaded, with no 「+」.
    const summary = screen.getByTestId("today-summary");
    expect(summary).toHaveTextContent("확인할 일 3");
    expect(summary).not.toHaveTextContent("3+");
  });

  it("「N+」 only when a read reports more than it returned", async () => {
    api.getInquiryQueueStrict.mockResolvedValue(queue({ totalElements: 80 }));
    draw();
    const summary = await screen.findByTestId("today-summary");
    await waitFor(() => expect(summary).toHaveTextContent("확인할 일 4+"));
    expect(screen.queryByLabelText("집계에서 빠진 곳")).toBeNull();
  });

  it("a failed latest run names the check the window does not include, and the line above stays three facts", async () => {
    draw(co({ lastRunStatus: "FAILED" }));
    const status = await screen.findByTestId("today-status");
    // The tally the failure qualifies is on `/customer-operations` now; the failure itself is work-shaped — it
    // says a part of this morning was not read — so it stays here, and it still names what it excludes.
    expect(status).not.toHaveTextContent("47건");
    expect(await screen.findByLabelText("집계에서 빠진 곳")).toHaveTextContent(
      "마지막 확인 실패 (오늘 14:02) · 이번 확인분 집계 제외 · 다음 확인 오늘 16:00",
    );
  });

  it("nothing waiting is 「없음」 with the next check — and no list", async () => {
    api.getInquiryQueueStrict.mockResolvedValue(queue({ content: [], totalElements: 0 }));
    draw(co({ decisions: { total: 0, rows: [] } }), null);
    // Nothing waiting is a state, and it is stated: the list's absence is not a sentence.
    await waitFor(() => expect(screen.getByText("지금 확인할 일이 없습니다.")).toBeTruthy());
    // When there is nothing to do, the line still says the job is running — that is the whole reason the empty
    // list can be trusted. It no longer says when the next check is; that is a schedule parameter.
    expect(await screen.findByTestId("today-status")).toHaveTextContent("자동 확인 중");
    expect(await screen.findByTestId("today-status")).not.toHaveTextContent("다음 확인");
    expect(screen.queryByRole("list", { name: "확인할 일" })).toBeNull();
  });

  it("a paused job says so and resumes on one press", async () => {
    api.resumeCustomerOperations.mockResolvedValue({});
    const { onChanged } = draw(co({ status: "PAUSED" }));
    expect(screen.getAllByText("일시정지됨").length).toBeGreaterThan(0);
    await userEvent.click(screen.getByRole("button", { name: "재개" }));
    await waitFor(() => expect(api.resumeCustomerOperations).toHaveBeenCalled());
    expect(onChanged).toHaveBeenCalled();
    // The status line is the date and the state; what the job did and what waits belong to a running job.
    const status = screen.getByTestId("today-status");
    expect(status).not.toHaveTextContent("실행 대기");
    expect(status).not.toHaveTextContent("최근 24시간 자동 확인");
  });

  /*
   * 실행 대기 — work the seller already decided and has not finished. Measured on the live org: three approved
   * replies standing seventeen days with no submission recorded, a fourth with four aborted attempts, and this
   * Home drew none of them because it never read `prepared` at all.
   */
  it("shows approved-but-unposted work and opens the screen that finishes it", async () => {
    draw();
    const section = await screen.findByRole("region", { name: "실행 대기" });

    expect(within(section).getByRole("link", { name: /컵 뚜껑 12oz/ }))
      .toHaveAttribute("href", "/reviews/reply/rev-approved");
    expect(section).toHaveTextContent("승인된 리뷰 답변");
  });

  it("does not offer anything 확인 필요 is already offering", async () => {
    draw();
    const section = await screen.findByRole("region", { name: "실행 대기" });
    const queueList = await screen.findByRole("region", { name: "확인할 일" });

    // The draft-ready inquiry is AWAITING_SELLER, so it is a 확인 필요 row — with 「초안 있음 · 미발송」 on it,
    // which says more than a second row here would. It must appear in exactly one of the two sections.
    expect(queueList).toHaveTextContent("뚜껑이 깨져서 왔어요");
    expect(section).not.toHaveTextContent("뚜껑이 깨져서 왔어요");
    expect(within(section).queryByRole("link", { name: /초안이 준비된 문의/ })).toBeNull();

    // And no row is offered from both places under two different links.
    const hrefs = [
      ...within(section).getAllByRole("link"),
      ...within(queueList).getAllByRole("link"),
    ].map((a) => a.getAttribute("href"));
    expect(new Set(hrefs).size).toBe(hrefs.length);
  });

  it("never dispatches: it links, and it holds no control that could post anything", async () => {
    draw();
    const section = await screen.findByRole("region", { name: "실행 대기" });

    expect(within(section).queryByRole("button")).toBeNull();
    expect(section).not.toHaveTextContent("보내기");
    expect(section).not.toHaveTextContent("발송");
    expect(section).not.toHaveTextContent("등록하기");
  });

  it("renders nothing when the operations read failed, rather than claiming nothing is waiting", async () => {
    draw(co(), null);
    await screen.findByRole("region", { name: "확인할 일" });
    expect(screen.queryByRole("region", { name: "실행 대기" })).toBeNull();
  });

  /*
   * The old contract here was 「one quiet line」: a single count plus, at most, the title of whichever problem
   * happened to carry a trend label. It is rewritten rather than deleted because what it was protecting — that a
   * repeated problem is NOT drawn as a task — is still true and is still asserted below. What changed is that the
   * line named nothing a seller could act on, and that the count it printed was a sum labelled as one of its parts.
   */
  it("names each repeated problem: the problem, the product, the evidence pair, and a way in", async () => {
    draw();
    const section = await screen.findByRole("region", { name: "반복 문제" });

    // The problem itself, linked to its own workspace — not to the list.
    expect(within(section).getByRole("link", { name: /포장 파손/ })).toHaveAttribute("href", "/memory/iss-1");
    expect(within(section).getByRole("link", { name: /접착 부족/ })).toHaveAttribute("href", "/memory/iss-2");

    // Why it is worth a look: the extractor's own words, never ours.
    expect(section).toHaveTextContent("조치 중");
    expect(section).toHaveTextContent("급증");

    // The product and the pair, never a rate.
    expect(section).toHaveTextContent("선바로 전선몰딩");
    expect(section).toHaveTextContent("리뷰 1,761건 중 16건이 이 문제를 말했습니다");
    expect(section).not.toHaveTextContent("%");
  });

  it("states the two populations as the server's sentence, and never adds them", async () => {
    draw();
    const section = await screen.findByRole("region", { name: "반복 문제" });

    // decidable = 1, observing = 1. The old line printed 「관찰 중 2」 — a sum under one part's name.
    expect(section).toHaveTextContent("지금 판단이 필요한 반복 문제가 1건 있습니다.");
    expect(section).not.toHaveTextContent("관찰 중 2");
  });

  it("dates a problem no trend fired on, so a stale pattern cannot read as a current one", async () => {
    draw();
    const section = await screen.findByRole("region", { name: "반복 문제" });

    // iss-2 carries no change label; without the span nothing on the row says when it last happened.
    expect(section).toHaveTextContent("근거 기간 2025-07-29 ~ 2026-08-19");
    // iss-1 does carry one, and does not get a second trend statement beside it.
    expect(section).not.toHaveTextContent("근거 기간 2026-08-01");
  });

  it("is still not a task: no verb, no button, and it sits below 확인 필요", async () => {
    const { container } = draw();
    const section = await screen.findByRole("region", { name: "반복 문제" });

    expect(within(section).queryByRole("button")).toBeNull();
    expect(section).not.toHaveTextContent("검토");
    expect(section).not.toHaveTextContent("답변");

    const list = container.querySelector('section[aria-label="확인할 일"]');
    expect(list).not.toBeNull();
    // Node.DOCUMENT_POSITION_FOLLOWING — the problems come after the work.
    expect(list!.compareDocumentPosition(section) & 4).toBeTruthy();
  });

  it("applies only where the job is open and has something to say", () => {
    expect(coHomeApplies(null)).toBe(false);
    expect(coHomeApplies(co({ available: false }))).toBe(false);
    expect(coHomeApplies(co({ status: null, eligible: false }))).toBe(false);
    expect(coHomeApplies(co({ status: null, eligible: true }))).toBe(true);
    expect(coHomeApplies(co())).toBe(true);
  });
});

/**
 * <b>The activation card has to answer three questions before a seller presses it</b> — what am I starting, what
 * will it do on its own, and will it talk to my customer. It answered none: the card printed `COPY.off`, the same
 * state word as the badge above it, over a button that said 「시작」.
 *
 * <p>The state word and the feature name are deliberately different things here. 「고객 운영 관리」 is the name every
 * surface uses (`RESPONSIBILITY_NAME`, unchanged); 「자동 확인」 is what the job DOES, and it belongs to the badge and
 * the button. A card whose title is the badge's word is a card that says the state twice and the name never.
 */
describe("activation card — what is being started, not the state again", () => {
  beforeEach(() => {
    Object.values(api).forEach((fn) => fn.mockReset());
    api.getInquiryQueueStrict.mockResolvedValue(queue());
  });

  const off = () => co({ status: null, lastCheckedAt: null, lastRunStatus: null, nextCheckAt: null });

  it("names the feature, states the cadence the server sent, and promises nothing is sent without approval", async () => {
    draw(off());
    const card = await screen.findByRole("region", { name: "자동 확인 꺼짐" });

    // The name — the canonical one, not a second spelling invented for this card.
    expect(within(card).getByText("고객 운영 관리")).toBeInTheDocument();
    // The cadence is the SERVER's number (`cadenceMinutes: 120` → 「2시간마다」); the sentence never spells it.
    expect(card).toHaveTextContent("2시간마다 연결된 채널의 리뷰와 문의를 확인해, 판단이 필요한 일만 정리합니다.");
    expect(card).toHaveTextContent("답변이나 외부 조치는 승인 전 자동 실행하지 않습니다.");
    expect(within(card).getByRole("button", { name: "자동 확인 시작" })).toBeEnabled();
  });

  it("does not print the badge's state word as the card's title", async () => {
    draw(off());
    const card = await screen.findByRole("region", { name: "자동 확인 꺼짐" });
    // The badge outside the card carries the state; the card's own heading line is the name.
    expect(within(card).queryByText("자동 확인 꺼짐")).toBeNull();
  });

  it("a 12-hour cadence says 12시간마다 — the sentence reads the view, never a constant", async () => {
    draw(co({ status: null, lastCheckedAt: null, nextCheckAt: null, cadenceMinutes: 720 }));
    const card = await screen.findByRole("region", { name: "자동 확인 꺼짐" });
    expect(card).toHaveTextContent("12시간마다 연결된 채널의");
  });

  it("paused keeps its own word and offers 재개 — the card still names the feature", async () => {
    draw(co({ status: "PAUSED" }));
    const card = await screen.findByRole("region", { name: "일시정지됨" });
    expect(within(card).getByText("고객 운영 관리")).toBeInTheDocument();
    expect(within(card).getByRole("button", { name: "재개" })).toBeInTheDocument();
  });

  it("no eligible source: the next step is connecting a channel, not starting a job that cannot read anything", async () => {
    draw(co({ status: null, eligible: false, lastCheckedAt: null, nextCheckAt: null }));
    const card = await screen.findByRole("region", { name: "자동 확인 꺼짐" });
    expect(within(card).queryByRole("button", { name: "자동 확인 시작" })).toBeNull();
    expect(within(card).getByRole("link", { name: "채널 연결" })).toBeInTheDocument();
  });

  it("pressing it activates — one call, and the page is told to re-read", async () => {
    api.activateCustomerOperations.mockResolvedValue({});
    const { onChanged } = draw(off());
    await userEvent.click(await screen.findByRole("button", { name: "자동 확인 시작" }));
    await waitFor(() => expect(api.activateCustomerOperations).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(onChanged).toHaveBeenCalled());
  });

  it("badge: 자동 확인 중 while ACTIVE", async () => {
    draw();
    expect(await screen.findByRole("link", { name: /자동 확인 중/ })).toBeInTheDocument();
  });
});

/**
 * <b>「중」 was a claim the screen could not support.</b> `lastCheckedAt` is the last FINISHED run's `finishedAt`,
 * so its absence means only that no check has completed — the view carries no status for an unfinished run, and the
 * screen therefore cannot tell a queued window from a running one. Measured on a local stack (2026-09-25) the one
 * run row sat at `PENDING`, `started_at` NULL, with no scheduler to pick it up: the cell said work was in progress
 * over a window that had never started. 「전」 is a fact about our own records and is true in every one of those states.
 */
describe("first check — before, not in progress", () => {
  beforeEach(() => {
    Object.values(api).forEach((fn) => fn.mockReset());
    api.getInquiryQueueStrict.mockResolvedValue(queue());
  });

  /**
   * <b>Both readings moved off this screen whole</b> (product-owner decision, 2026-09-26) — 「첫 확인 전」 and the
   * tally that replaces it are the same cell in two states, and `CustomerOperations.test.tsx` pins both there.
   * What these keep is the half that is about the Home: neither state may come back to this line, and whichever
   * one is true, the Home still says whether the job is running.
   */
  it("says neither 첫 확인 전 nor the tally — both belong to the screen that owns the job", async () => {
    draw(co({ lastCheckedAt: null, lastRunStatus: null }));
    const before = await screen.findByTestId("today-status");
    expect(before).toHaveTextContent("자동 확인 중");
    expect(before).not.toHaveTextContent("첫 확인");
    cleanup();
    draw();
    const after = await screen.findByTestId("today-status");
    expect(after).toHaveTextContent("자동 확인 중");
    expect(after).not.toHaveTextContent("47건");
    expect(after).not.toHaveTextContent("첫 확인");
  });
});

describe("waitLabel", () => {
  it("minutes, hours, days — and a bare date is days in Korea time", () => {
    expect(waitLabel("2026-09-16T05:10:00Z", NOW)).toBe("20분 대기");
    expect(waitLabel("2026-09-16T02:30:00Z", NOW)).toBe("3시간 대기");
    expect(waitLabel("2026-09-13T05:30:00Z", NOW)).toBe("3일 대기");
    expect(waitLabel("2026-09-16", NOW)).toBe("오늘 접수");
    expect(waitLabel("2026-09-14", NOW)).toBe("2일 대기");
    expect(waitLabel(null, NOW)).toBeNull();
  });
});

/**
 * <b>Reference-based hierarchy v1</b> — the Home opens on the list, not on a card about the list.
 *
 * <p>Measured at 1440×900 before this: a bordered band with two 571px cells, both values at 22px/800, so an
 * obligation the seller did NOT have was drawn at the size and width of the eleven items that were. Put beside
 * Linear's Triage list and Intercom's Inbox, neither draws a counter over a work list at all. The band is gone
 * and every fact it held is on the one status line — which is what these pin.
 */
describe("오늘 — an inbox, not a dashboard", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.getInquiryQueueStrict.mockResolvedValue(queue());
  });

  /** The same Home with nothing already decided and unfinished. */
  function noAwaiting(): OperationsHome {
    const o = ops();
    return {
      ...o,
      prepared: { ...o.prepared, reviewRepliesApproved: 0, rows: o.prepared.rows.filter((r) => r.kind !== "REVIEW_REPLY") },
    } as never;
  }

  it("the summary is one surface of obligation cells, never the counter band that was removed", async () => {
    // `container` was destructured for the «no svg anywhere» check, which is now scoped to the band — the
    // header above it carries one control, 새로고침, whose mark is an svg.
    draw();
    await screen.findByTestId("today-status");
    // <b>The band stays gone</b> — what stands here is a Pulse, not the card it replaced (product-owner
    // decision, 2026-09-26). The band had this name and its own counter cells; nothing may take either back.
    expect(screen.queryByLabelText("오늘 요약")).toBeNull();
    const summary = await screen.findByTestId("today-summary");
    // ONE object: the surface carries the fill, and no cell inside it carries a box of its own.
    // <b>The grid is sized to the cells it has</b> (UI System v2): this fixture makes no overview read, so
    // 현재 미답변 is absent rather than 「0」 and two facts are two columns. Reserving a third would leave a
    // gap where the row of figures is supposed to read as a row.
    // Thirds whatever the read returned, and ruled rather than filled — see the same two re-points in
    // `OperationsSummary.test.tsx`. A cell with nothing measured to say still draws nothing.
    expect(summary.className).toContain("grid-cols-3");
    // The third slot stands and says it could not be measured, rather than leaving a third of a ruled
    // band blank — see `OperationsSummary.test.tsx` for the per-cause wording.
    expect(summary.querySelector("[data-testid='pulse-unanswered']")).toHaveTextContent("—");
    expect(summary.className).not.toContain("bg-canvas");
    expect(summary.className).not.toMatch(/rounded|shadow|gradient/);
    const cells = [...summary.querySelectorAll("[data-testid^='pulse-']")] as HTMLElement[];
    expect(cells.length).toBeGreaterThan(0);
    for (const cell of cells) expect(cell.className).not.toMatch(/border|bg-|rounded|shadow/);
    expect(summary.querySelectorAll("[class*='rounded'],[class*='border'],[class*='shadow']")).toHaveLength(0);
    // Every cell answers its question at the same size, number or sentence, and nothing on this surface
    // is larger — the customers' sentences below stay the subject of the screen, and the band is read
    // before them, not instead of them.
    //
    // <p><b>`max-w-3xl` is gone and that is the change, not a loss</b> (Home visual target, 2026-10-01).
    // The cap bought «the columns do not stand 344px apart», and it bought it by making the band a
    // 768px object inside a 1120px column — which is what made it read as a card on the page rather than
    // as a strip of it. The target rules it full-width at thirds, so the columns are a third apart by
    // construction and the rules land where the page's own rules do.
    for (const cell of cells) expect((cell.children[1] as HTMLElement).className).toContain("text-title");
    expect(summary.querySelectorAll("[class*='text-3xl']")).toHaveLength(0);
    // And no chart, no icon, by construction: nothing in the band is drawn.
    expect(summary.querySelectorAll("svg")).toHaveLength(0);
  });

  it("실행 대기 0 is a quiet fact and not a control — there is nothing to point at", async () => {
    draw(co(), noAwaiting());
    const summary = await screen.findByTestId("today-summary");
    // <b>「0건」, not 「없음」</b> (product-owner decision, 2026-10-01). Every slot in this band now reads
    // as either a counted figure or 「—」, and a measured zero belongs firmly on the figure side — the job
    // IS running and it found nothing pending. Spelling it as a word put it in the same visual class as
    // the unmeasured cells beside it, which is the one distinction this band exists to keep.
    expect(summary).toHaveTextContent("실행 대기 0건");
    expect(summary).not.toHaveTextContent("실행 대기 —");
    expect(within(summary).queryByRole("link", { name: /실행 대기/ })).toBeNull();
  });

  it("실행 대기 > 0 is its own obligation cell and the pointer to its section", async () => {
    draw();
    const summary = await screen.findByTestId("today-summary");
    expect(within(summary).getByRole("link", { name: /실행 대기/ })).toHaveAttribute("href", "#실행-대기");
    expect(summary).toHaveTextContent("실행 대기 2");
    // Promotion is the section existing, not a cell up here.
    expect(screen.getByRole("heading", { name: "실행 대기" })).toBeTruthy();
  });

  it("the way to the rest of the list stands on the heading, before the rows", async () => {
    // Enough that the list is certainly longer than it draws, whatever `HOME_ROWS` is: the assertion below is
    // about what the link SAYS, and it must not become vacuous the next time that constant moves.
    const extra = Array.from({ length: HOME_ROWS + 2 }, (_, i) => ({
      workItemId: `w-${i + 4}`, inquiryId: `i-${i + 4}`, sellerAccountId: "a", channelId: "ch", channelCode: "CAFE24",
      channelNameKo: "카페24", productId: null, productName: null, phase: "OPEN", status: "UNANSWERED",
      title: `추가 문의 ${i + 1}`, snippet: null, receivedAt: `2026-09-16T04:${31 + i}:00Z`, hasDraft: false,
    }));
    api.getInquiryQueueStrict.mockResolvedValue(queue({ content: [...queue().content, ...extra] as never }));
    draw();
    // <b>The link is a destination and names no number</b> (product-owner decision, 2026-09-26). The total is
    // the Pulse's, once, in one form including 「N+」; saying it again here put the same fact twice on one screen
    // and made the control change its wording for a reason no seller could see.
    // Scoped to the brief: since the target gave 실행 대기 and 반복 문제 the same three-slot header, three
    // sections on this screen carry a 「전체 보기 →」, and an unscoped query would be asking the page which
    // one it meant. The assertion is the brief's and is unchanged.
    const brief = await screen.findByRole("region", { name: "확인할 일" });
    const more = within(brief).getByRole("link", { name: "전체 보기 →" });
    expect(more).toHaveAttribute("href", "/customer-operations/cases");
    expect(more.textContent).not.toMatch(/\d/);
    // The Pulse still owns it, and still marks a floor as a floor.
    expect(screen.getByTestId("pulse-work")).toHaveTextContent(/확인할 일 \d/);
    const firstRow = screen.getByText("뚜껑이 깨져서 왔어요");
    expect(more.compareDocumentPosition(firstRow) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });
});

/**
 * <b>The Pulse took the room the seventh row depended on</b> (product-owner decision, 2026-09-26).
 *
 * <p>Measured at 1152×720: the list's scroller ends at y=631 and seven rows used to end at 585 — 46px of slack
 * against a band that costs 91px. Seven and the Pulse are not both true there. What these pin is which of the
 * two gave way and how: <b>a row is whole or it is absent</b>, and the visible limit is a named function of the
 * layout rather than a measurement taken at render time.
 */
describe("visible rows — the Pulse's cost, paid in whole rows", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.getInquiryQueueStrict.mockResolvedValue(queue());
  });

  it("is a pure function of the layout, and the narrow answer is one row shorter", () => {
    expect(visibleHomeRows(true)).toBe(HOME_ROWS);
    expect(visibleHomeRows(false)).toBe(HOME_ROWS_NARROW);
    // Not a second capacity: `HOME_ROWS` is still what the wide case shows and what 「+N」 counts against.
    expect(HOME_ROWS_NARROW).toBe(HOME_ROWS - 1);
  });

  it("an environment that cannot measure says narrow — it under-fills a wide screen, it never clips a narrow one", async () => {
    // jsdom answers `matches: false` to every width query, which is the same answer a server render gives.
    // The safe direction is the short one: a missing row is visibly missing, a clipped row pretends to be there.
    const extra = Array.from({ length: HOME_ROWS + 4 }, (_, i) => ({
      workItemId: `w-${i + 40}`, inquiryId: `i-${i + 40}`, sellerAccountId: "a", channelId: "ch", channelCode: "CAFE24",
      channelNameKo: "카페24", productId: null, productName: null, phase: "OPEN", status: "UNANSWERED",
      title: `줄 세우기 ${i + 1}`, snippet: null, receivedAt: `2026-09-16T04:${10 + i}:00Z`, hasDraft: false,
    }));
    api.getInquiryQueueStrict.mockResolvedValue(queue({ content: [...queue().content, ...extra] as never }));
    draw();
    // Both the section and its list carry the canonical noun, which is the point of the naming decision;
    // the rows are the list's.
    const list = (await screen.findAllByLabelText("확인할 일")).find((el) => el.tagName === "UL");
    await waitFor(() => expect(list?.querySelectorAll("li").length).toBe(HOME_ROWS_NARROW));
  });

  it("the way out says where it goes and names no number, floor or not", async () => {
    // The total is the Pulse's, in one form. This link used to say 「전체 11건 보기」 and fall silent on 「N+」,
    // so the same control changed its wording for a reason no seller could see.
    api.getInquiryQueueStrict.mockResolvedValue(queue({ totalElements: 80 }));
    draw();
    const brief = await screen.findByRole("region", { name: "확인할 일" });
    const more = within(brief).getByRole("link", { name: "전체 보기 →" });
    expect(more).toHaveAttribute("href", "/customer-operations/cases");
    expect(more.textContent).not.toMatch(/\d/);
    expect(screen.getByTestId("pulse-work")).toHaveTextContent("확인할 일 4+");
  });
});
