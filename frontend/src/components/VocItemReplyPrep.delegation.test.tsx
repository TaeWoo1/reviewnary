// @vitest-environment jsdom
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { VocItemReplyPrep } from "./VocItemReplyPrep";
import { api } from "../lib/apiClient";
import { expectNoAxeViolations } from "../test/axe";
import type { ReviewReplyDelegation, ReviewReplyPrep } from "../lib/types";

vi.mock("../lib/apiClient", () => ({
  api: {
    getReviewReplyPrep: vi.fn(),
    saveReviewReplyDraft: vi.fn(),
    decideReviewReplyApproval: vi.fn(),
    handOverReviewReply: vi.fn(),
    dropReplySetAside: vi.fn(),
    cancelReviewReplyDelegation: vi.fn(),
  },
}));

// The delegate lane on the review panel (contract §4, owner decisions D9 · D10, D7 as revised 2026-10-25). The
// controls are drawn from the server's `delegation`, the one press approves the version on screen, and a delegate's
// report never reads as a proven post.

const REVIEW = "0b6f2b1e-5f0a-4c9b-9a51-7d2f7f6e0c11";
const REF = `review:${REVIEW}`;
const DRAFT = {
  version: 1,
  body: "합성-저장된-초안",
  contentFingerprint: "mock-abc",
  fingerprintAlgorithm: "review-reply-v1",
  createdAt: "2026-10-21T00:00:00Z",
};

function prepView(delegation: ReviewReplyDelegation | null, canApprove = true): ReviewReplyPrep {
  return {
    actionRef: REF,
    channelReplyState: "PENDING",
    productName: "합성 상품",
    reviewDate: "2026-10-19",
    rating: 2,
    draftAuthorKind: "SELLER",
    draftEvidence: [],
    draftAnswerBasis: null,
    draftAnswerBasisNote: null,
    redactedBody: "합성-리뷰-본문",
    bodyRedacted: false,
    triageDisposition: "RESPONSE_NEEDED",
    suggestion: {
      body: "합성-추천-초안",
      category: "general_reply",
      providerKind: "RULE_BASED",
      providerName: "review-reply-template",
      providerVersion: "templates-v1",
    },
    draft: DRAFT,
    approval: null,
    outcome: null,
    capabilities: { canSave: true, canApprove, canWithdraw: false, canCopy: false, canStartSubmissionRun: false },
    delegation,
  };
}

const READY: ReviewReplyDelegation = { taskState: null, editedOnMobile: false, canSend: true, canCancel: false };

function mount() {
  return render(<VocItemReplyPrep accountId="mock-acct-1" actionRef={REF} disposition="RESPONSE_NEEDED" />);
}

describe("VocItemReplyPrep · delegate lane", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("offers one press beside an outlined 승인, says what it means, and approves nothing until pressed", async () => {
    vi.mocked(api.getReviewReplyPrep).mockResolvedValue(prepView(READY));
    const { container } = mount();

    const handOver = await screen.findByRole("button", { name: "답글 등록 요청" });
    expect(handOver.className).toContain("bg-brand-700");
    expect(screen.queryByRole("button", { name: /휴대폰/ })).toBeNull();
    expect(screen.queryByRole("button", { name: "초안 저장" })).toBeNull();
    expect(screen.getByText("이 문장 그대로 승인 · 담당자가 네이버에 등록")).toBeTruthy();
    expect(screen.getByTestId("draft-status").textContent).toBe("검사 통과");
    expect(screen.getByRole("button", { name: "승인" }).className).not.toContain("bg-brand-700");
    expect(api.handOverReviewReply).not.toHaveBeenCalled();
    await expectNoAxeViolations(container);
  });

  it("hands over the review by its id and re-reads the panel", async () => {
    const user = userEvent.setup();
    vi.mocked(api.getReviewReplyPrep).mockResolvedValueOnce(prepView(READY)).mockResolvedValue(
      prepView({ taskState: "APPROVED", editedOnMobile: false, canSend: false, canCancel: true }, false),
    );
    vi.mocked(api.handOverReviewReply).mockResolvedValue({
      reviewId: REVIEW,
      state: "APPROVED",
      draftVersion: 1,
      editedOnMobile: false,
      outcomeRecorded: false,
    });
    mount();

    await user.click(await screen.findByRole("button", { name: "답글 등록 요청" }));
    expect(api.handOverReviewReply).toHaveBeenCalledWith(REVIEW, 1);
    expect(await screen.findByText("담당자 대기")).toBeTruthy();
    expect(screen.queryByRole("button", { name: "승인" })).toBeNull();
    expect(screen.queryByRole("button", { name: "답글 등록 요청" })).toBeNull();
    expect(screen.getByRole("button", { name: "요청 취소" })).toBeTruthy();
  });

  it("saves an edit by itself after a pause, and the press waits for it", async () => {
    const user = userEvent.setup();
    vi.mocked(api.getReviewReplyPrep).mockResolvedValue(prepView(READY));
    vi.mocked(api.saveReviewReplyDraft).mockResolvedValue({ ...DRAFT, version: 2 });
    mount();

    const editor = await screen.findByLabelText("답변 초안");
    await user.type(editor, " 추가");
    expect(screen.getByTestId("draft-status").textContent).toBe("저장 중…");
    const handOver = screen.getByRole("button", { name: "답글 등록 요청" });
    expect(handOver.getAttribute("aria-disabled")).toBe("true");
    await user.click(handOver);
    expect(api.handOverReviewReply).not.toHaveBeenCalled();
    await waitFor(
      () =>
        expect(api.saveReviewReplyDraft).toHaveBeenCalledWith("mock-acct-1", REF, {
          body: "합성-저장된-초안 추가",
          baseVersion: 1,
        }),
      { timeout: 3000 },
    );
  });

  it("says when the draft is the phone's edit", async () => {
    vi.mocked(api.getReviewReplyPrep).mockResolvedValue(prepView({ ...READY, draftFromPhone: true }));
    mount();

    expect((await screen.findByTestId("draft-status")).textContent).toMatch(/^휴대폰에서 고침 · \d{2}:\d{2}$/);
  });

  it("keeps this PC's words aside when the phone's edit led, and lets the seller choose", async () => {
    const user = userEvent.setup();
    vi.mocked(api.getReviewReplyPrep).mockResolvedValue(
      prepView({
        ...READY,
        draftFromPhone: true,
        setAside: { version: 2, body: "이 PC에서 쓰던 합성 문장", writtenAt: "2026-10-25T01:00:00Z" },
      }),
    );
    vi.mocked(api.saveReviewReplyDraft).mockResolvedValue({ ...DRAFT, version: 4 });
    const { container } = mount();

    const aside = await screen.findByRole("group", { name: "이 PC에서 쓰던 문장" });
    expect(aside.textContent).toContain("이 PC에서 쓰던 합성 문장");
    expect(screen.getByRole("button", { name: "답글 등록 요청" }).getAttribute("aria-disabled")).toBe("true");
    await expectNoAxeViolations(container);

    await user.click(screen.getByRole("button", { name: "휴대폰 문장 쓰기" }));
    expect(api.dropReplySetAside).toHaveBeenCalledWith(REVIEW);

    await user.click(screen.getByRole("button", { name: "이 PC 문장 쓰기" }));
    expect(api.saveReviewReplyDraft).toHaveBeenCalledWith("mock-acct-1", REF, {
      body: "이 PC에서 쓰던 합성 문장",
      baseVersion: 1,
    });
  });

  it("keeps the words being typed aside when a save finds the phone's edit already there", async () => {
    const user = userEvent.setup();
    vi.mocked(api.getReviewReplyPrep)
      .mockResolvedValueOnce(prepView(READY))
      .mockResolvedValue({
        ...prepView({ ...READY, draftFromPhone: true }),
        draft: { ...DRAFT, version: 2, body: "휴대폰에서 고친 합성 문장" },
      });
    vi.mocked(api.saveReviewReplyDraft).mockRejectedValue({
      response: { status: 409, data: { message: "이미 최신 초안이 있습니다. 새로고침 후 다시 시도하세요." } },
    });
    mount();

    const editor = await screen.findByLabelText("답변 초안");
    await user.type(editor, " 추가");
    await user.tab();

    const aside = await screen.findByRole("group", { name: "이 PC에서 쓰던 문장" });
    expect(aside.textContent).toContain("합성-저장된-초안 추가");
    expect((editor as HTMLTextAreaElement).value).toBe("휴대폰에서 고친 합성 문장");
  });

  it("sends nothing while the panel's own write is in flight, though the buttons stay focusable", async () => {
    const user = userEvent.setup();
    vi.mocked(api.getReviewReplyPrep).mockResolvedValue(
      prepView({ taskState: "AWAITING_APPROVAL", editedOnMobile: false, canSend: true, canCancel: false }),
    );
    vi.mocked(api.decideReviewReplyApproval).mockReturnValue(new Promise(() => {}));
    mount();

    await user.click(await screen.findByRole("button", { name: "승인" }));
    const handOver = screen.getByRole("button", { name: "답글 등록 요청" });
    await waitFor(() => expect(handOver.getAttribute("aria-disabled")).toBe("true"));
    await user.click(handOver);
    expect(api.handOverReviewReply).not.toHaveBeenCalled();
    expect(screen.queryByRole("button", { name: "요청 취소" })).toBeNull();
  });

  it("says a reported post is the delegate's report, not a proven state", async () => {
    vi.mocked(api.getReviewReplyPrep).mockResolvedValue(
      prepView({ taskState: "POSTED", editedOnMobile: true, canSend: false, canCancel: false }, false),
    );
    const { container } = mount();

    const chip = await screen.findByText("등록함 · 네이버 확인 전");
    expect(chip.className).not.toContain("text-good");
    expect(screen.getByText("담당자의 보고 · 다음 엑셀에서 네이버 확인")).toBeTruthy();
    expect(screen.queryByRole("button", { name: "승인" })).toBeNull();
    await expectNoAxeViolations(container);
  });

  it("shows the server's own refusal sentence and re-reads", async () => {
    const user = userEvent.setup();
    vi.mocked(api.getReviewReplyPrep).mockResolvedValue(
      prepView({ taskState: "AWAITING_APPROVAL", editedOnMobile: false, canSend: true, canCancel: false }, false),
    );
    vi.mocked(api.handOverReviewReply).mockRejectedValue({
      response: { status: 409, data: { message: "휴대폰에서 문장이 바뀌어 승인하지 않았습니다. 바뀐 문장을 보고 다시 누르세요." } },
    });
    mount();

    await user.click(await screen.findByRole("button", { name: "답글 등록 요청" }));
    expect(await screen.findByRole("alert")).toHaveProperty(
      "textContent",
      "휴대폰에서 문장이 바뀌어 승인하지 않았습니다. 바뀐 문장을 보고 다시 누르세요.",
    );
    await waitFor(() => expect(api.getReviewReplyPrep).toHaveBeenCalledTimes(2));
  });

  it("says when it was requested and cancels by the review's id", async () => {
    const user = userEvent.setup();
    vi.mocked(api.getReviewReplyPrep).mockResolvedValue(
      prepView(
        { taskState: "CLAIMED", editedOnMobile: false, canSend: false, canCancel: true, since: "2026-10-25T01:12:00Z" },
        false,
      ),
    );
    vi.mocked(api.cancelReviewReplyDelegation).mockResolvedValue({
      reviewId: REVIEW,
      state: "CANCEL_REQUESTED",
      draftVersion: 1,
      editedOnMobile: false,
      outcomeRecorded: false,
    });
    mount();

    expect(await screen.findByText("등록하는 중")).toBeTruthy();
    expect(screen.getByText(/^\d{2}:\d{2} 요청$/)).toBeTruthy();
    await user.click(screen.getByRole("button", { name: "요청 취소" }));
    expect(api.cancelReviewReplyDelegation).toHaveBeenCalledWith(REVIEW);
  });

  // Owner decision 2026-10-23 (mockup confirmed the same day): a hold always says what changed, which sentence, and
  // what to do — and a document edit that touches nothing keeps the approval with one quiet line.
  const PROMISE_NOTE = {
    codes: ["UNSUPPORTED_PROMISE"],
    sources: [{ kind: "ORG_POLICY", title: "교환 · 반품 안내", changedAt: "2026-10-22T06:40:00Z", removed: false }],
    promises: ["교환"],
    sentences: ["세탁 후 수축이면 무료 교환해 드리겠습니다."],
  };

  it("says why an approval was held, marks its sentence, and lets the seller send a fixed draft", async () => {
    vi.mocked(api.getReviewReplyPrep).mockResolvedValue(
      prepView({ taskState: "HELD", editedOnMobile: false, canSend: true, canCancel: true, note: PROMISE_NOTE }, false),
    );
    const { container } = mount();

    expect(await screen.findByText("고칠 곳 1")).toBeTruthy();
    const why = screen.getByRole("region", { name: "왜 멈췄나요" });
    expect(why.textContent).toContain("운영 정책 「교환 · 반품 안내」");
    expect(why.textContent).toContain("답글의 「교환」 약속이 지금 근거에는 없습니다.");
    expect(why.querySelector("mark")?.textContent).toBe("세탁 후 수축이면 무료 교환해 드리겠습니다.");
    expect(why.textContent).toContain("문장을 고쳐 다시 승인하세요.");
    expect(screen.getByRole("button", { name: "답글 등록 요청" })).toBeTruthy();
    await expectNoAxeViolations(container);
  });

  it("tells the seller a claimed reply's delegate was asked to stop", async () => {
    vi.mocked(api.getReviewReplyPrep).mockResolvedValue(
      prepView(
        { taskState: "HOLD_REQUESTED", editedOnMobile: false, canSend: false, canCancel: true, note: PROMISE_NOTE },
        false,
      ),
    );
    mount();

    expect(await screen.findByText("멈춤 요청 중")).toBeTruthy();
    expect(screen.getByText("담당자가 등록 전인지 확인하면 바뀝니다")).toBeTruthy();
    expect(screen.getByRole("region", { name: "왜 멈췄나요" })).toBeTruthy();
  });

  it("names the edited document of a sent-back draft and admits it cannot point at the sentence", async () => {
    vi.mocked(api.getReviewReplyPrep).mockResolvedValue(
      prepView(
        {
          taskState: "RECHECK_NEEDED",
          editedOnMobile: false,
          canSend: true,
          canCancel: true,
          note: {
            codes: ["SOURCE_CHANGED"],
            sources: [{ kind: "PRODUCT_KNOWLEDGE", title: "전기 주전자 · 사용 안내", changedAt: "2026-10-22T06:40:00Z", removed: false }],
            promises: [],
            sentences: [],
          },
        },
        false,
      ),
    );
    mount();

    const why = await screen.findByRole("region", { name: "왜 멈췄나요" });
    expect(why.textContent).toContain("상품 지식 「전기 주전자 · 사용 안내」");
    expect(why.textContent).toContain("어느 문장이 달라졌는지는 아직 짚지 못합니다.");
    expect(why.querySelector("mark")).toBeNull();
    expect(screen.queryByText(/응대 기준이 바뀌어 이 초안은 승인할 수 없습니다/)).toBeNull();
  });

  it("keeps an approval whose cited document was only edited, with one quiet line", async () => {
    vi.mocked(api.getReviewReplyPrep).mockResolvedValue(
      prepView(
        {
          taskState: "APPROVED",
          editedOnMobile: false,
          canSend: false,
          canCancel: true,
          note: {
            codes: ["SOURCE_CHANGED"],
            sources: [{ kind: "PRODUCT_KNOWLEDGE", title: "린넨 커튼 · 소재 안내", changedAt: "2026-10-22T06:40:00Z", removed: false }],
            promises: [],
            sentences: [],
          },
        },
        false,
      ),
    );
    mount();

    expect(await screen.findByText("담당자 대기")).toBeTruthy();
    expect(screen.getByText(/이 답글과 부딪히는 곳이 없어 승인을 그대로 둡니다/)).toBeTruthy();
    expect(screen.queryByRole("region", { name: "왜 멈췄나요" })).toBeNull();
  });

  it("shows a phone edit's unsupported promise as something to check before approval, not as a hold", async () => {
    vi.mocked(api.getReviewReplyPrep).mockResolvedValue(
      prepView(
        { taskState: "AWAITING_APPROVAL", editedOnMobile: true, canSend: true, canCancel: true, note: { ...PROMISE_NOTE, sources: [] } },
        false,
      ),
    );
    mount();

    const check = await screen.findByRole("region", { name: "확인할 점" });
    expect(check.textContent).toContain("승인하기 전에 이 약속이 맞는지 보세요.");
    expect(screen.queryByRole("region", { name: "왜 멈췄나요" })).toBeNull();
  });

  it("says once, quietly, that the seller approved a promise they were shown", async () => {
    vi.mocked(api.getReviewReplyPrep).mockResolvedValue(
      prepView(
        { taskState: "APPROVED", editedOnMobile: true, canSend: false, canCancel: true, note: { ...PROMISE_NOTE, sources: [] } },
        false,
      ),
    );
    mount();

    expect(await screen.findByText(/확인하고 승인한 답글입니다 — 근거에 없는 약속 「교환」/)).toBeTruthy();
    expect(screen.queryByRole("region", { name: "왜 멈췄나요" })).toBeNull();
    expect(screen.queryByRole("region", { name: "확인할 점" })).toBeNull();
  });

  it("names a forbidden phrase and marks its sentence when an approval is held for it", async () => {
    vi.mocked(api.getReviewReplyPrep).mockResolvedValue(
      prepView(
        {
          taskState: "HELD",
          editedOnMobile: false,
          canSend: true,
          canCancel: true,
          note: { codes: ["FORBIDDEN_PHRASE"], sources: [], promises: [], sentences: ["고객님 탓이 아닙니다."], phrases: ["고객님 탓"] },
        },
        false,
      ),
    );
    mount();

    const why = await screen.findByRole("region", { name: "왜 멈췄나요" });
    expect(why.textContent).toContain("회사가 쓰지 않기로 한 표현이 있습니다: 「고객님 탓」");
    expect(why.querySelector("mark")?.textContent).toBe("고객님 탓이 아닙니다.");
    expect(why.textContent).toContain("그 표현을 빼고 문장을 고쳐 다시 승인하세요.");
    expect(why.textContent).not.toContain("약속이 지금 근거에는 없습니다");
  });

  it("draws nothing of the lane for a review that cannot use it", async () => {
    vi.mocked(api.getReviewReplyPrep).mockResolvedValue(prepView(null));
    mount();

    expect((await screen.findByRole("button", { name: "승인" })).className).toContain("bg-brand-700");
    expect(screen.queryByTestId("delegated-reply")).toBeNull();
  });
});
