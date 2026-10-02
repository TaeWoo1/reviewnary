// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import userEvent from "@testing-library/user-event";
import type { CustomerOperationsDecisionRow } from "../../lib/customerOperationsTypes";
import { expectNoAxeViolations } from "../../test/axe";

const api = vi.hoisted(() => ({
  getCustomerOperationsDecisions: vi.fn(),
  getCustomerOperationsHome: vi.fn(),
  getOperationsHomeStrict: vi.fn(),
  getInquiryQueueStrict: vi.fn(),
  getReviewWorkStrict: vi.fn((): Promise<unknown> => Promise.reject(new Error("not in this test"))),
  // The pane beside the list reads the selected case; it never settles here — the list is what is under test.
  getOperationsCase: vi.fn(() => new Promise(() => undefined)),
}));
vi.mock("../../lib/apiClient", () => ({ api, getToken: () => null }));

import { OperationsCaseQueue } from "./OperationsCaseQueue";
import { REASON, reasonOfCase } from "../../lib/copy/customerOps";

const NOW = new Date("2026-09-22T05:30:00Z"); // 14:30 KST

function row(over: Partial<CustomerOperationsDecisionRow> = {}): CustomerOperationsDecisionRow {
  return {
    caseId: "c-1",
    subjectKind: "INQUIRY",
    channelNameKo: "카페24",
    title: "교환 신청은 언제까지 가능한가요?",
    rating: null,
    reasonNote: "고객이 답변을 기다리고 있습니다",
    summary: "등록된 지식으로 답변할 수 있는 문의입니다.",
    recommendedActionType: "REPLY_TO_CUSTOMER",
    recommendedAction: null,
    missingInformation: [],
    draftPrepared: true,
    decidedBy: "RULE",
    openedAt: "2026-09-21T04:00:00Z",
    receivedOn: null,
    to: "/inquiries/i-1",
    ...over,
  };
}

const REVIEW = row({
  caseId: "c-2",
  subjectKind: "REVIEW",
  channelNameKo: "네이버",
  title: "배송이 너무 늦었어요",
  rating: 2,
  reasonNote: "확인이 필요한 리뷰입니다",
  summary: null,
  recommendedActionType: null,
  draftPrepared: false,
  openedAt: "2026-09-20T04:00:00Z",
  receivedOn: null,
  to: "/reviews/reply/r-2",
});

/**
 * The three reads the Home makes, empty by default.
 *
 * <p>This screen used to read cases alone while the Home's 「확인 필요」 merged cases, flagged reviews and the inquiry
 * queue — measured on the demo org, the Home said 27건 and this screen said 1건 under the same name. It now draws the
 * same list with the same composer, so the fixtures have to supply the same three reads.
 */
function reads(over: { co?: unknown; ops?: unknown; queue?: unknown } = {}) {
  api.getCustomerOperationsHome.mockResolvedValue(
    over.co ?? { available: true, eligible: true, status: "ACTIVE", cadenceMinutes: 120, lastCheckedAt: null,
      lastRunStatus: null, nextCheckAt: null, sources: [], decisions: { total: 0, rows: [] },
      handled: { since: null, autoResolved: 0, monitoring: 0, draftsPrepared: 0, verifying: 0, rows: [], checked: 0 },
      gaps: { total: 0, rows: [] } },
  );
  api.getOperationsHomeStrict.mockResolvedValue(
    over.ops ?? { reviews: { needsAttentionUndecided: 0, needsAttentionTotal: 0, watchTotal: 0, rows: [] },
      problems: { decidable: 0, observing: 0, dormant: 0, rows: [] },
      prepared: { reviewRepliesApproved: 0, inquiryDraftsReady: 0, improvementDraftsReady: 0, rows: [] },
      collection: [] },
  );
  api.getInquiryQueueStrict.mockResolvedValue(over.queue ?? { content: [], totalElements: 0 });
}

function draw() {
  return render(
    <MemoryRouter>
      <OperationsCaseQueue now={NOW} />
    </MemoryRouter>,
  );
}

describe("OperationsCaseQueue", () => {
  beforeEach(() => vi.resetAllMocks());

  /**
   * <b>Re-pointed: the order is each lane's own, not one wait across all of them</b> (product-owner
   * decision, 2026-10-01). This asserted 「기다린 순서대로」, and that single comparator is what the
   * Home's visual QA failed on — it inverted the review lane's server-declared order and ranked a
   * review's authoring date against an inquiry's receipt time.
   *
   * <p>The guarantee that was being made here survives whole: an inquiry and a review stand in ONE
   * list under one name, with each channel named. Both of these rows are cases, so the order they
   * stand in is the case lane's, which is the server's own.
   */
  it("문의와 리뷰가 한 목록에 함께 선다 — 종류로 갈라지지 않는다", async () => {
    reads();
    api.getCustomerOperationsDecisions.mockResolvedValue({ total: 2, rows: [row(), REVIEW] });
    draw();

    const list = await screen.findByRole("list", { name: "확인할 일" });
    const items = within(list).getAllByRole("listitem");
    expect(items).toHaveLength(2);
    // The case lane, in the order the server sent it.
    expect(items[0]).toHaveTextContent("교환 신청은 언제까지 가능한가요?");
    expect(items[1]).toHaveTextContent("배송이 너무 늦었어요");
    expect(list).toHaveTextContent("카페24");
    expect(list).toHaveTextContent("네이버");
  });

  it("케이스 행은 케이스 화면을 연다 — 종류별로 갈라지지 않는다", async () => {
    reads();
    api.getCustomerOperationsDecisions.mockResolvedValue({ total: 2, rows: [row(), REVIEW] });
    draw();

    await screen.findByRole("list", { name: "확인할 일" });
    const links = screen.getAllByRole("link");
    // A case opens its case screen whatever its subject is — the investigation is what the seller came for, and
    // splitting cases by subject kind would be two queues wearing one name.
    expect(links.map((a) => a.getAttribute("href"))).toEqual([
      "/customer-operations/cases/c-1",
      "/customer-operations/cases/c-2",
    ]);
  });

  /**
   * <b>The defect this screen was fixed for.</b> The Home's list is cases + flagged reviews + the inquiry queue,
   * deduplicated by owning screen; this screen read cases alone and called itself the same thing. On the demo org
   * that was 27건 against 1건, with 확인할 일 — the sidebar entry a seller reaches for first — pointing at the 1.
   */
  it("홈과 같은 목록을 그린다 — 케이스만이 아니라 리뷰와 문의도 선다", async () => {
    reads({
      ops: {
        reviews: {
          needsAttentionUndecided: 1, needsAttentionTotal: 1, watchTotal: 0,
          rows: [{ reviewId: "r-9", accountId: "a-1", channelCode: "NAVER", rating: 1,
                   occurredOn: "2026-09-19", productName: "전선몰딩", quote: "접착이 약해요" }],
        },
        problems: { decidable: 0, observing: 0, dormant: 0, rows: [] },
        prepared: { reviewRepliesApproved: 0, inquiryDraftsReady: 0, improvementDraftsReady: 0, rows: [] },
        collection: [],
      },
      queue: {
        totalElements: 1,
        content: [{ inquiryId: "i-9", workItemId: "w-9", channelCode: "CAFE24", channelNameKo: "카페24",
                    title: "배송 언제 되나요?", snippet: null, hasDraft: false, phase: "OPEN",
                    receivedAt: "2026-09-18T04:00:00Z" }],
      },
    });
    api.getCustomerOperationsDecisions.mockResolvedValue({ total: 1, rows: [row()] });
    draw();

    const list = await screen.findByRole("list", { name: "확인할 일" });
    expect(list).toHaveTextContent("접착이 약해요");
    expect(list).toHaveTextContent("배송 언제 되나요?");
    expect(list).toHaveTextContent("교환 신청은 언제까지 가능한가요?");
    // Each row opens the screen that owns it — the same identity the Home dedupes by.
    const hrefs = screen.getAllByRole("link").map((a) => a.getAttribute("href"));
    // A review carries the way back to this list (UI/UX v2 Phase 1) — the Review Case offers 「← 확인할 일」 then.
    expect(hrefs).toContain("/reviews/reply/r-9?from=work");
    expect(hrefs).toContain("/inquiries/i-9");
    expect(hrefs).toContain("/customer-operations/cases/c-1");
  });

  /**
   * <b>Re-pointed to the state word, which is where this fact now stands</b> (product-owner decision,
   * 2026-10-01). It was 「초안 있음 · 미발송」 in the row's second line, 104px right of a lead badge
   * reading 초안 준비됨 off the same `draftPrepared` field — the same claim twice, in the slot the eye
   * reads as customer context. The guarantee is unchanged: <b>a prepared draft must never read as a
   * sent one</b>, and `WORK_STATE.DRAFT_READY`'s own definition is 「reviewnary wrote something; the
   * seller has not decided anything about it」.
   */
  it("준비된 초안은 아직 보내지 않았다고 말한다", async () => {
    reads();
    api.getCustomerOperationsDecisions.mockResolvedValue({ total: 1, rows: [row()] });
    draw();

    const list = await screen.findByRole("list", { name: "확인할 일" });
    expect(list).toHaveTextContent("초안 준비됨");
    // Said once. Nothing on the row claims it went out, and nothing restates the badge.
    expect(list).not.toHaveTextContent("초안 있음");
    expect(list).not.toHaveTextContent("발송함");
    expect(list).not.toHaveTextContent("답변함");
  });

  it("읽지 못한 목록과 빈 목록은 다른 문장이다", async () => {
    reads();
    api.getCustomerOperationsHome.mockRejectedValue(new Error("boom"));
    api.getOperationsHomeStrict.mockRejectedValue(new Error("boom"));
    api.getInquiryQueueStrict.mockRejectedValue(new Error("boom"));
    api.getCustomerOperationsDecisions.mockRejectedValue(new Error("boom"));
    const failed = draw();
    expect(await screen.findByRole("alert")).toHaveTextContent("불러오지 못했습니다");
    expect(screen.queryByText(/확인이 필요한 문의나 리뷰가 없습니다/)).toBeNull();
    failed.unmount();

    reads();
    api.getCustomerOperationsDecisions.mockResolvedValue({ total: 0, rows: [] });
    draw();
    expect(await screen.findByText(/확인이 필요한 문의나 리뷰가 없습니다/)).toBeInTheDocument();
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it("한 번에 읽는 깊이보다 많으면 그렇게 말하고, 다른 화면으로 보내지 않는다", async () => {
    reads();
    api.getCustomerOperationsDecisions.mockResolvedValue({ total: 80, rows: [row(), REVIEW] });
    draw();

    await screen.findByRole("list", { name: "확인할 일" });
    // It says the depth it reached, not a total: the reads that overflow count different populations, and their
    // sum is a number nobody measured.
    const note = screen.getByText(/2건까지 보여 드립니다/);
    expect(note).toHaveTextContent("처리하시면 다음 건이 올라옵니다");
    expect(note.textContent).not.toMatch(/문의 화면|리뷰 화면/);
  });

  it("리뷰 행도 무엇을 하면 되는지 말한다 — 「판단 보류」로 비워 두지 않는다", async () => {
    // The review lane prepares this sentence with no model call, from the issue memory another pipeline already
    // wrote. Before it reached the row, a review stood here carrying only 「확인이 필요한 리뷰입니다」 beside an
    // inquiry that said what was ready to send — and was tagged 「판단 보류」, which described the row as emptier
    // than it was.
    reads();
    api.getCustomerOperationsDecisions.mockResolvedValue({
      total: 1,
      rows: [
        {
          ...REVIEW,
          recommendedAction:
            "이 상품에서 「포장 파손」 문제가 3건 확인됐습니다. 개별 응대보다 상품 설명이나 운영 기준을 함께 손보는 편이 빠릅니다.",
        },
      ],
    });
    draw();

    const list = await screen.findByRole("list", { name: "확인할 일" });
    /*
      <b>Re-pointed: the sentence moved off the row, the TAG is what was being defended</b>
      (product-owner decision, 2026-10-01 — 「내부 workflow metadata는 기본 행에서 제거」).

      <p>This test was written against 「판단 보류」, a tag that described the row as emptier than it
      was, and it proved the point by finding the recommendation sentence in the row. The tag is still
      what it fixed — `reasonOfCase` reads `recommendedActionType`, untouched — and the row still says
      what it is. What may no longer stand in the row's second line is reviewnary's own prose about
      what to do; that is the case screen's, which the row opens.
    */
    expect(list).toHaveTextContent("리뷰");
    expect(list).not.toHaveTextContent("판단 보류");
    expect(list).not.toHaveTextContent(/「포장 파손」 문제가 3건 확인됐습니다/);
  });

  /**
   * `REPLY_TO_CUSTOMER` is the one recommendation in this vocabulary that Reviewnary DID take on itself — it names
   * an action on the customer, not a judgement the seller must first make, and `DECISION` has always read it as
   * 「답변 확인 후 발송」. Tagging the same field 「판단 보류」 in the list made one DTO field say two opposite things:
   * measured, the demo org's one case carried a prepared draft and 「등록된 지식으로 답변할 수 있는 문의입니다」
   * under a 「판단 보류」 tag. 「답변 필요」 is the tag the raw inquiry rows already wear for the same work.
   */
  it("답변이 준비된 문의 케이스는 「답변 필요」다 — 「판단 보류」가 아니다", async () => {
    reads();
    api.getCustomerOperationsDecisions.mockResolvedValue({ total: 1, rows: [row()] });
    draw();

    const list = await screen.findByRole("list", { name: "확인할 일" });
    /*
      <b>Re-pointed: the tag is the guarantee, and the row is no longer where it is read</b> (canonical
      mockup semantic correction, 2026-10-02).

      <p>What this test defends is `reasonOfCase` — a `REPLY_TO_CUSTOMER` case is 답변 필요 and not
      판단 보류 — so it now asserts that function directly, where the fact lives. The row stopped
      DRAWING the word for a reason this package exists for: 답변 필요 is a work-state word, the lead
      column owns it, and this row's own state is 초안 준비됨. Printing both put two states on one row
      and the false one first.
    */
    expect(reasonOfCase("REPLY_TO_CUSTOMER", [], "INQUIRY")).toBe(REASON.reply);
    expect(list).not.toHaveTextContent("판단 보류");
    expect(list).toHaveTextContent("초안 준비됨");
    // And the word the lead column owns is never ALSO drawn as this row's category.
    expect(within(list).queryByText("답변 필요")).toBeNull();
  });

  it("준비된 것이 없는 리뷰는 없는 추천을 지어내지 않는다", async () => {
    reads();
    api.getCustomerOperationsDecisions.mockResolvedValue({ total: 1, rows: [REVIEW] });
    draw();

    const list = await screen.findByRole("list", { name: "확인할 일" });
    /*
      <b>Re-pointed to the stronger form of the same rule</b> (product-owner decision, 2026-10-01).
      It asserted the fallback to `reasonNote` — 「확인이 필요한 리뷰입니다」, the same sentence on every
      review case — because the point was that nothing is invented when there is no recommendation.
      The second line is customer context now and this case carries no `summary`, so there is NO
      second line, which is the strongest version of 「없는 추천을 지어내지 않는다」: the row says the
      state, the customer's own words and where it came from, and stops.
    */
    expect(list).toHaveTextContent("확인 필요");
    expect(list).toHaveTextContent("배송이 너무 늦었어요");
    expect(list).not.toHaveTextContent("확인이 필요한 리뷰입니다");
  });

  it("행마다 버튼이 없다 — 넓은 화면에서 행은 선택이고, 한 가지 주 행동은 오른쪽 상세에 있다", async () => {
    const restore = stubWide(true);
    try {
      reads();
      api.getCustomerOperationsDecisions.mockResolvedValue({ total: 2, rows: [row(), REVIEW] });
      draw();
      const list = await screen.findByRole("list", { name: "확인할 일" });
      // No row carries a verb of its own: the list used to end every row in 「검토」.
      expect(within(list).queryByText("검토")).toBeNull();
      // Each row selects in place.
      const links = within(list).getAllByRole("link");
      expect(links.every((a) => /[?&]item=/.test(a.getAttribute("href") ?? ""))).toBe(true);
    } finally {
      restore();
    }
  });

  /**
   * <b>Review Decision UX v3.2 — product-owner decision.</b> This screen used to open the first row and the
   * assertion above used to say so. It is a screen for looking through — 45 rows, five filters, a 5,407px
   * list — so nothing is chosen until the seller chooses it, and the seller can put it back.
   */
  it("아무것도 선택하지 않은 채로 열리고, 고른 뒤에는 닫을 수 있다", async () => {
    const restore = stubWide(true);
    try {
      reads();
      api.getCustomerOperationsDecisions.mockResolvedValue({ total: 2, rows: [row(), REVIEW] });
      draw();
      const list = await screen.findByRole("list", { name: "확인할 일" });
      const links = within(list).getAllByRole("link");

      // Nothing is selected, and no pane stands for a row nobody picked.
      expect(links.some((a) => a.getAttribute("aria-current") === "true")).toBe(false);
      expect(screen.queryByLabelText("선택한 확인할 일")).toBeNull();
      expect(screen.queryByRole("button", { name: /닫기/ })).toBeNull();

      // A press selects that row — and only that row.
      await userEvent.click(links[0]);
      const after = within(await screen.findByRole("list", { name: "확인할 일" })).getAllByRole("link");
      expect(after[0]).toHaveAttribute("aria-current", "true");
      expect(after[1]).not.toHaveAttribute("aria-current", "true");
      expect(await screen.findByLabelText("선택한 확인할 일")).toBeInTheDocument();

      // …and 닫기 puts the screen back to where it opened.
      await userEvent.click(screen.getByRole("button", { name: /닫기/ }));
      expect(screen.queryByLabelText("선택한 확인할 일")).toBeNull();
      const closed = within(await screen.findByRole("list", { name: "확인할 일" })).getAllByRole("link");
      expect(closed.some((a) => a.getAttribute("aria-current") === "true")).toBe(false);
    } finally {
      restore();
    }
  });

  it("이 화면에는 결정하는 컨트롤이 없다", async () => {
    reads();
    api.getCustomerOperationsDecisions.mockResolvedValue({ total: 2, rows: [row(), REVIEW] });
    draw();

    const list = await screen.findByRole("list", { name: "확인할 일" });
    // Every row is a link to the screen that owns the decision; nothing here resolves, dismisses or sends.
    expect(within(list).queryAllByRole("button")).toHaveLength(0);
    /* The guarantee, where it now lives: every button on this screen only changes what is DRAWN. The
       assertion used to be 「all of them are inside the view group」, and the canonical mockup adds a
       second such control beside the title — the search toggle, which narrows the same rows. So the
       check names both populations rather than the one container they used to share, and nothing that
       resolves, dismisses or sends has a way onto this screen. */
    const views = screen.getByRole("group", { name: "확인할 일 보기" });
    const search = screen.getByRole("button", { name: "검색" });
    for (const button of screen.queryAllByRole("button")) {
      if (button === search) continue;
      expect(views).toContainElement(button);
    }
  });

  /**
   * <b>One item, one elapsed time — on the row and in the pane beside it</b> (elapsed-time contract,
   * 2026-10-02).
   *
   * <p>Rendered rather than computed, because the defect was never in the arithmetic: the two sides read
   * two different timestamps. This stands the row and its own detail side by side, as the screen does at
   * 1200px and up, and reads the string off both.
   */
  it("같은 item의 목록과 pane은 같은 경과 시간을 말한다", async () => {
    const restore = stubWide(true);
    try {
      reads();
      // The customer wrote on 09-18; reviewnary opened the case on 09-20. The list used to date it by
      // the second and the pane by the first — two numbers for one item.
      const subject = row({ receivedOn: "2026-09-18", openedAt: "2026-09-20T01:00:00Z" });
      api.getCustomerOperationsDecisions.mockResolvedValue({ total: 1, rows: [subject] });
      api.getOperationsCase.mockResolvedValue({
        caseId: subject.caseId,
        open: true,
        subjectKind: "INQUIRY",
        channelNameKo: "카페24",
        productName: null,
        productScopeAvailable: false,
        receivedOn: subject.receivedOn,
        openedAt: subject.openedAt,
        rating: null,
        title: subject.title,
        body: "교환 신청은 언제까지 가능한가요?",
        reasonNote: subject.reasonNote,
        disposition: "NEEDS_DECISION",
        decidedBy: "AGENT",
        summary: null,
        recommendedActionType: "REPLY_TO_CUSTOMER",
        recommendedAction: null,
        missingInformation: [],
        whyDecisionNeeded: null,
        investigated: [],
        knowledgeUsed: [],
        gap: null,
        draft: null,
        to: "/inquiries/i-1",
      });
      draw();

      const list = await screen.findByRole("list", { name: "확인할 일" });
      const rowText = within(list).getAllByRole("listitem")[0].textContent ?? "";
      const elapsed = rowText.match(/\d+일 (대기|전)/)?.[0];
      expect(elapsed).toBe("4일 대기"); // 09-18 → 09-22 KST, the CUSTOMER's clock

      await userEvent.click(within(list).getAllByRole("link")[0]);
      const pane = await screen.findByRole("article", { name: "선택한 항목" });
      expect(within(pane).getByText(elapsed!)).toBeTruthy();
    } finally {
      restore();
    }
  });

  it("접근성 위반 0", async () => {
    reads();
    api.getCustomerOperationsDecisions.mockResolvedValue({ total: 2, rows: [row(), REVIEW] });
    const { container } = draw();
    await screen.findByRole("list", { name: "확인할 일" });
    await expectNoAxeViolations(container);
  });
});

/** Stands the list and the detail side by side, as the layout does at 1200px and up. Returns the restore. */
function stubWide(matches: boolean): () => void {
  const original = window.matchMedia;
  window.matchMedia = ((query: string) => ({
    matches,
    media: query,
    onchange: null,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
    addListener: () => undefined,
    removeListener: () => undefined,
    dispatchEvent: () => false,
  })) as unknown as typeof window.matchMedia;
  return () => {
    window.matchMedia = original;
  };
}

describe("OperationsCaseQueue — the seller's own reply work (UI/UX v2 Phase 3)", () => {
  it("lists reply work before approval beside the rest, and an unattributable account declines rather than reading as none", async () => {
    api.getCustomerOperationsHome.mockResolvedValue(null);
    api.getCustomerOperationsDecisions.mockResolvedValue(null);
    api.getOperationsHomeStrict.mockResolvedValue(null);
    api.getInquiryQueueStrict.mockResolvedValue(null);
    api.getReviewWorkStrict.mockResolvedValue({
      attentionTotal: 0,
      attention: [],
      committed: [
        {
          accountId: "acc-nv", channelCode: "NAVER", channelNameKo: "네이버 스마트스토어", coverage: "COVERED",
          todo: [{
            channelCode: "NAVER", channelNameKo: "네이버 스마트스토어", sourceType: "REVIEW", productName: "선바로",
            rating: 4, replyStatus: "PENDING", sourceCreatedDate: "2026-08-28", collectedDate: "2026-08-29",
            signalType: "LOW_RATING_REVIEW", safePreview: "잘 떨어져요", actionRef: "review:r-1", reviewId: "r-1",
            triageDisposition: "RESPONSE_NEEDED", hasReplyPreparation: true, replyWorkState: "AWAITING_APPROVAL",
            category: null, hasReportedSubmission: false,
          }],
          recentlyReported: [],
        },
        { accountId: "acc-2", channelCode: "CAFE24", channelNameKo: "카페24", coverage: "UNCERTAIN_MULTI_ACCOUNT", todo: [], recentlyReported: [] },
      ],
    });
    render(
      <MemoryRouter>
        <OperationsCaseQueue />
      </MemoryRouter>,
    );
    const list = await screen.findByRole("list", { name: "확인할 일" });
    const row = within(list).getByRole("link", { name: /잘 떨어져요/ });
    expect(row).toHaveTextContent("승인 대기");
    expect(row).toHaveAttribute("href", "/reviews/reply/r-1?from=work");
    expect(screen.getByTestId("reply-work-coverage-uncertain")).toHaveTextContent("안전하게 판단할 수 없어요");
  });
});

describe("OperationsCaseQueue — views of the one list (UI/UX v2 Phase 4)", () => {
  beforeEach(() => vi.resetAllMocks());

  function replyItem(reviewId: string, state: "AWAITING_APPROVAL" | "DRAFT_NEEDED", preview: string) {
    return {
      channelCode: "NAVER", channelNameKo: "네이버 스마트스토어", sourceType: "REVIEW", productName: "선바로",
      rating: 3, replyStatus: "PENDING", sourceCreatedDate: "2026-08-28", collectedDate: "2026-08-29",
      signalType: "LOW_RATING_REVIEW", safePreview: preview, actionRef: `review:${reviewId}`, reviewId,
      triageDisposition: "RESPONSE_NEEDED", hasReplyPreparation: state === "AWAITING_APPROVAL", replyWorkState: state,
      category: null, hasReportedSubmission: false,
    };
  }

  it("narrows by a fact each row carries, keeps the order, and never counts past the list", async () => {
    reads();
    api.getCustomerOperationsDecisions.mockResolvedValue({ total: 2, rows: [row(), REVIEW] });
    api.getReviewWorkStrict.mockResolvedValue({
      attentionTotal: 0,
      attention: [],
      committed: [
        {
          accountId: "acc-nv", channelCode: "NAVER", channelNameKo: "네이버 스마트스토어", coverage: "COVERED",
          todo: [replyItem("r-7", "AWAITING_APPROVAL", "승인을 기다리는 답변"), replyItem("r-8", "DRAFT_NEEDED", "초안이 필요한 답변")],
          recentlyReported: [],
        },
      ],
    });
    draw();

    const views = await screen.findByRole("group", { name: "확인할 일 보기" });
    // Every view says how many rows it holds, out of the same four.
    expect(within(views).getByRole("button", { name: "전체 4" })).toHaveAttribute("aria-pressed", "true");
    expect(within(views).getByRole("button", { name: "답변할 문의 1" })).toBeInTheDocument();
    expect(within(views).getByRole("button", { name: "확인할 리뷰 1" })).toBeInTheDocument();
    expect(within(views).getByRole("button", { name: "승인할 일 1" })).toBeInTheDocument();
    expect(within(views).getByRole("button", { name: "초안 필요 1" })).toBeInTheDocument();

    await userEvent.click(within(views).getByRole("button", { name: "승인할 일 1" }));
    const list = screen.getByRole("list", { name: "확인할 일" });
    expect(within(list).getAllByRole("link")).toHaveLength(1);
    expect(within(list).getByRole("link", { name: /승인을 기다리는 답변/ })).toBeInTheDocument();

    await userEvent.click(within(views).getByRole("button", { name: "답변할 문의 1" }));
    expect(within(screen.getByRole("list", { name: "확인할 일" })).getByRole("link", { name: /교환 신청/ })).toBeInTheDocument();

    // The header still counts the whole list: a view never passes itself off as the total.
    expect(screen.getByText("4건")).toBeInTheDocument();
  });
});
