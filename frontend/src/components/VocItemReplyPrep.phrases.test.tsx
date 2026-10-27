// @vitest-environment jsdom
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { VocItemReplyPrep } from "./VocItemReplyPrep";
import { api } from "../lib/apiClient";
import { expectNoAxeViolations } from "../test/axe";
import { phraseBlockSentence, replacePhrase, segments, withoutSentence } from "./reviews/PhraseFindings";
import type { ReviewReplyDelegation, ReviewReplyPhraseFinding, ReviewReplyPrep } from "../lib/types";

vi.mock("../lib/apiClient", () => ({
  api: {
    // 「이 리뷰에 맞는 안내」 (2026-10-27): nothing registered, so the block draws nothing.
    getReviewGuidance: () => Promise.resolve({ attached: [], rejected: [], pickable: [] }),
    getReviewReplyPrep: vi.fn(),
    saveReviewReplyDraft: vi.fn(),
    decideReviewReplyApproval: vi.fn(),
    handOverReviewReply: vi.fn(),
    dropReplySetAside: vi.fn(),
    cancelReviewReplyDelegation: vi.fn(),
  },
}));

// 걸린 표현 in the review panel (owner decision 2026-10-23, mockup ②): a ban is fixed before approval or sending, a
// preference offers the seller's own replacement and may stay. Every change is the seller's press, saved as theirs.

const REVIEW = "0b6f2b1e-5f0a-4c9b-9a51-7d2f7f6e0c11";
const REF = `review:${REVIEW}`;
const BANNED_SENTENCE = "고객님 탓이 아니라 택배사 사정으로 늦어졌습니다.";
const SOFT_SENTENCE = "죄송하지만 다음에는 더 빨리 보내 드리겠습니다.";
const BODY = `늦어서 죄송합니다. ${BANNED_SENTENCE} ${SOFT_SENTENCE}`;
const FINDINGS: ReviewReplyPhraseFinding[] = [
  { kind: "FORBIDDEN", phrase: "고객님 탓", replacement: null, sentence: BANNED_SENTENCE },
  { kind: "AVOID", phrase: "죄송하지만", replacement: "번거로우시겠지만", sentence: SOFT_SENTENCE },
];

function prepView(body: string, findings: ReviewReplyPhraseFinding[],
                  delegation: ReviewReplyDelegation | null = null): ReviewReplyPrep {
  return {
    actionRef: REF, channelReplyState: "PENDING", productName: "전기 주전자", reviewDate: "2026-10-19", rating: 2,
    draftAuthorKind: "MODEL", draftEvidence: [], draftAnswerBasis: null, draftAnswerBasisNote: null,
    redactedBody: "배송이 너무 늦었어요", bodyRedacted: false, triageDisposition: "RESPONSE_NEEDED",
    suggestion: { body: "합성-추천", category: "general_reply", providerKind: "RULE_BASED",
      providerName: "review-reply-template", providerVersion: "templates-v1" },
    draft: { version: 3, body, contentFingerprint: "mock", fingerprintAlgorithm: "review-reply-v1",
      createdAt: "2026-10-23T00:00:00Z" },
    approval: null, outcome: null,
    capabilities: { canSave: true, canApprove: true, canWithdraw: false, canCopy: false, canStartSubmissionRun: false },
    delegation, phraseFindings: findings,
  };
}

function mount() {
  return render(<VocItemReplyPrep accountId="mock-acct-1" actionRef={REF} disposition="RESPONSE_NEEDED" />);
}

describe("VocItemReplyPrep · 걸린 표현", () => {
  beforeEach(() => vi.clearAllMocks());

  it("marks both kinds where they stand, keeps 승인 shut for the ban and says what may stay", async () => {
    vi.mocked(api.getReviewReplyPrep).mockResolvedValue(prepView(BODY, FINDINGS));
    const { container } = mount();

    const block = await screen.findByLabelText("걸린 표현");
    expect(within(block).getByText("고객님 탓").getAttribute("data-kind")).toBe("FORBIDDEN");
    expect(within(block).getByText("죄송하지만").getAttribute("data-kind")).toBe("AVOID");
    expect(within(block).getByText("「고객님 탓」 — 이 문장을 고쳐야 승인할 수 있어요.")).toBeTruthy();
    expect(within(block).getByText("「죄송하지만」 → 「번거로우시겠지만」")).toBeTruthy();
    const approve = screen.getByRole("button", { name: "답글 확정" });
    expect(approve.getAttribute("aria-disabled")).toBe("true");
    await userEvent.click(approve);
    expect(api.decideReviewReplyApproval).not.toHaveBeenCalled();
    expect(screen.getByText("「고객님 탓」을 고치면 승인할 수 있어요. 「죄송하지만」은 그대로 둬도 됩니다.")).toBeTruthy();
    await expectNoAxeViolations(container);
  });

  it("drops the banned sentence on the seller's press and saves it as their next version", async () => {
    const fixed = `늦어서 죄송합니다. ${SOFT_SENTENCE}`;
    vi.mocked(api.getReviewReplyPrep).mockResolvedValueOnce(prepView(BODY, FINDINGS))
      .mockResolvedValue(prepView(fixed, [FINDINGS[1]]));
    vi.mocked(api.saveReviewReplyDraft).mockResolvedValue(undefined as never);
    mount();

    await userEvent.click(await screen.findByRole("button", { name: "이 문장 빼기" }));
    expect(api.saveReviewReplyDraft).toHaveBeenCalledWith("mock-acct-1", REF, { body: fixed, baseVersion: 3 });
    await waitFor(() => expect(screen.getByRole("button", { name: "답글 확정" }).getAttribute("aria-disabled")).toBe("false"));
    expect(screen.queryByText(/을 고치면 승인할 수 있어요/)).toBeNull();
  });

  it("swaps in the seller's own replacement, or keeps the phrase — a preference stops nothing", async () => {
    vi.mocked(api.getReviewReplyPrep).mockResolvedValue(prepView(BODY, [FINDINGS[1]]));
    vi.mocked(api.saveReviewReplyDraft).mockResolvedValue(undefined as never);
    mount();

    expect((await screen.findByRole("button", { name: "답글 확정" })).getAttribute("aria-disabled")).toBe("false");
    await userEvent.click(screen.getByRole("button", { name: "그대로 두기" }));
    expect(screen.queryByLabelText("걸린 표현")).toBeNull();
    expect(api.saveReviewReplyDraft).not.toHaveBeenCalled();
  });

  it("직접 고치기 puts the cursor on the phrase in the editor", async () => {
    vi.mocked(api.getReviewReplyPrep).mockResolvedValue(prepView(BODY, FINDINGS));
    mount();

    await userEvent.click(await screen.findByRole("button", { name: "직접 고치기" }));
    const editor = screen.getByLabelText("답글 내용") as HTMLTextAreaElement;
    expect(document.activeElement).toBe(editor);
    expect(editor.value.slice(editor.selectionStart, editor.selectionEnd)).toBe("고객님 탓");
  });

  it("keeps the delegate's press shut for a ban and says why once, beneath it", async () => {
    vi.mocked(api.getReviewReplyPrep).mockResolvedValue(
      prepView(BODY, FINDINGS, { taskState: null, editedOnMobile: false, canSend: true, canCancel: false }));
    mount();

    const handOver = await screen.findByRole("button", { name: "답글 등록 요청" });
    expect(handOver.getAttribute("aria-disabled")).toBe("true");
    expect(screen.getByTestId("draft-status").textContent).toBe("고칠 곳 1");
    await userEvent.click(handOver);
    expect(api.handOverReviewReply).not.toHaveBeenCalled();
    expect(screen.getAllByText(/을 고치면 승인할 수 있어요/)).toHaveLength(1);
  });
});

describe("PhraseFindings helpers", () => {
  it("replaces the phrase however it was spaced, and drops a sentence closing the gap", () => {
    expect(replacePhrase("죄송 하지만 다시 보내 드립니다.", "죄송하지만", "번거로우시겠지만"))
      .toBe("번거로우시겠지만 다시 보내 드립니다.");
    expect(withoutSentence(BODY, BANNED_SENTENCE)).toBe(`늦어서 죄송합니다. ${SOFT_SENTENCE}`);
    expect(withoutSentence(BODY, "없는 문장")).toBe(BODY);
  });

  it("cuts the draft into plain and marked pieces, the ban winning an overlap", () => {
    const pieces = segments("고객님 탓이 아닙니다.", [
      { kind: "AVOID", phrase: "탓이", replacement: null, sentence: null },
      { kind: "FORBIDDEN", phrase: "고객님 탓", replacement: null, sentence: null },
    ]);
    expect(pieces).toEqual([{ text: "고객님 탓", kind: "FORBIDDEN" }, { text: "이 아닙니다.", kind: null }]);
  });

  it("says nothing when only preferences are found, and picks each particle", () => {
    expect(phraseBlockSentence([FINDINGS[1]])).toBeNull();
    expect(phraseBlockSentence([{ kind: "FORBIDDEN", phrase: "무조건 회수", replacement: null, sentence: null }]))
      .toBe("「무조건 회수」를 고치면 승인할 수 있어요.");
  });
});
