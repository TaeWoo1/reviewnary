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
    requestPhoneApproval: vi.fn(),
    cancelReviewReplyDelegation: vi.fn(),
  },
}));

// The delegate lane on the review panel (contract §4, owner decisions D7 · D9 · D10). The controls are drawn from
// the server's `delegation`, every press is the seller's, and a delegate's report never reads as a proven post.

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

  it("offers both sends beside an outlined 승인, and sends nothing until pressed", async () => {
    vi.mocked(api.getReviewReplyPrep).mockResolvedValue(prepView(READY));
    const { container } = mount();

    const handOver = await screen.findByRole("button", { name: "승인하고 담당자에게 맡기기" });
    expect(handOver.className).toContain("bg-brand-700");
    expect(screen.getByRole("button", { name: "휴대폰으로 승인 요청" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "승인" }).className).not.toContain("bg-brand-700");
    expect(api.handOverReviewReply).not.toHaveBeenCalled();
    expect(api.requestPhoneApproval).not.toHaveBeenCalled();
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

    await user.click(await screen.findByRole("button", { name: "승인하고 담당자에게 맡기기" }));
    expect(api.handOverReviewReply).toHaveBeenCalledWith(REVIEW);
    expect(await screen.findByText("승인됨 · 담당자 등록 대기")).toBeTruthy();
    expect(screen.queryByRole("button", { name: "승인" })).toBeNull();
    expect(screen.queryByRole("button", { name: "승인하고 담당자에게 맡기기" })).toBeNull();
    expect(screen.getByRole("button", { name: "요청 취소" })).toBeTruthy();
  });

  it("waits for an unsaved edit: the press sends the saved draft only", async () => {
    const user = userEvent.setup();
    vi.mocked(api.getReviewReplyPrep).mockResolvedValue(prepView(READY));
    mount();

    const editor = await screen.findByLabelText("답변 초안");
    await user.type(editor, " 추가");
    const phone = screen.getByRole("button", { name: "휴대폰으로 승인 요청" });
    expect(phone.getAttribute("aria-disabled")).toBe("true");
    await user.click(phone);
    expect(api.requestPhoneApproval).not.toHaveBeenCalled();
    expect(screen.getAllByText("저장하지 않은 변경이 있습니다. 먼저 초안을 저장하세요.").length).toBeGreaterThan(0);
  });

  it("sends nothing while the panel's own write is in flight, though the buttons stay focusable", async () => {
    const user = userEvent.setup();
    vi.mocked(api.getReviewReplyPrep).mockResolvedValue(
      prepView({ taskState: "AWAITING_APPROVAL", editedOnMobile: false, canSend: true, canCancel: true }),
    );
    vi.mocked(api.decideReviewReplyApproval).mockReturnValue(new Promise(() => {}));
    mount();

    await user.click(await screen.findByRole("button", { name: "승인" }));
    const handOver = screen.getByRole("button", { name: "승인하고 담당자에게 맡기기" });
    await waitFor(() => expect(handOver.getAttribute("aria-disabled")).toBe("true"));
    await user.click(handOver);
    await user.click(screen.getByRole("button", { name: "고친 초안 다시 보내기" }));
    await user.click(screen.getByRole("button", { name: "요청 취소" }));
    expect(api.handOverReviewReply).not.toHaveBeenCalled();
    expect(api.requestPhoneApproval).not.toHaveBeenCalled();
    expect(api.cancelReviewReplyDelegation).not.toHaveBeenCalled();
  });

  it("says a reported post is the delegate's report, not a proven state", async () => {
    vi.mocked(api.getReviewReplyPrep).mockResolvedValue(
      prepView({ taskState: "POSTED", editedOnMobile: true, canSend: false, canCancel: false }, false),
    );
    const { container } = mount();

    const chip = await screen.findByText("담당자가 등록함 · 확인 전");
    expect(chip.className).not.toContain("text-good");
    expect(screen.getByText("담당자의 보고입니다. 판매자센터에서 직접 확인한 것은 아닙니다.")).toBeTruthy();
    expect(screen.getByText("휴대폰에서 고침")).toBeTruthy();
    expect(screen.queryByRole("button", { name: "승인" })).toBeNull();
    await expectNoAxeViolations(container);
  });

  it("shows the server's own refusal sentence and re-reads", async () => {
    const user = userEvent.setup();
    vi.mocked(api.getReviewReplyPrep).mockResolvedValue(
      prepView({ taskState: "AWAITING_APPROVAL", editedOnMobile: false, canSend: true, canCancel: true }, false),
    );
    vi.mocked(api.handOverReviewReply).mockRejectedValue({
      response: { status: 409, data: { message: "답글은 보냈지만 승인하지 못했습니다. 휴대폰에서 승인하거나 다시 시도하세요." } },
    });
    mount();

    expect(await screen.findByRole("button", { name: "고친 초안 다시 보내기" })).toBeTruthy();
    await user.click(screen.getByRole("button", { name: "승인하고 담당자에게 맡기기" }));
    expect(await screen.findByRole("alert")).toHaveProperty(
      "textContent",
      "답글은 보냈지만 승인하지 못했습니다. 휴대폰에서 승인하거나 다시 시도하세요.",
    );
    await waitFor(() => expect(api.getReviewReplyPrep).toHaveBeenCalledTimes(2));
  });

  it("cancels by the review's id", async () => {
    const user = userEvent.setup();
    vi.mocked(api.getReviewReplyPrep).mockResolvedValue(
      prepView({ taskState: "CLAIMED", editedOnMobile: false, canSend: false, canCancel: true }, false),
    );
    vi.mocked(api.cancelReviewReplyDelegation).mockResolvedValue({
      reviewId: REVIEW,
      state: "CANCEL_REQUESTED",
      draftVersion: 1,
      editedOnMobile: false,
      outcomeRecorded: false,
    });
    mount();

    await user.click(await screen.findByRole("button", { name: "요청 취소" }));
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

    expect(await screen.findByText("승인 보류 · 다시 승인 필요")).toBeTruthy();
    const why = screen.getByRole("region", { name: "왜 멈췄나요" });
    expect(why.textContent).toContain("운영 정책 「교환 · 반품 안내」");
    expect(why.textContent).toContain("답글의 「교환」 약속이 지금 근거에는 없습니다.");
    expect(why.querySelector("mark")?.textContent).toBe("세탁 후 수축이면 무료 교환해 드리겠습니다.");
    expect(why.textContent).toContain("문장을 고쳐 다시 승인하세요.");
    expect(screen.getByRole("button", { name: "고친 초안 다시 보내기" })).toBeTruthy();
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

    expect(await screen.findByText("승인 보류 · 담당자 확인 중")).toBeTruthy();
    expect(screen.getByText(/담당자에게도 멈춤을 전했습니다/)).toBeTruthy();
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

    expect(await screen.findByText("승인됨 · 담당자 등록 대기")).toBeTruthy();
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
    expect(check.textContent).toContain("휴대폰에서 승인하기 전에 이 약속이 맞는지 확인하세요.");
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

    expect(await screen.findByText(/근거에 없는 「교환」 약속을 확인하고 승인한 답글입니다/)).toBeTruthy();
    expect(screen.queryByRole("region", { name: "왜 멈췄나요" })).toBeNull();
    expect(screen.queryByRole("region", { name: "확인할 점" })).toBeNull();
  });

  it("draws nothing of the lane for a review that cannot use it", async () => {
    vi.mocked(api.getReviewReplyPrep).mockResolvedValue(prepView(null));
    mount();

    expect((await screen.findByRole("button", { name: "승인" })).className).toContain("bg-brand-700");
    expect(screen.queryByTestId("delegated-reply")).toBeNull();
  });
});
