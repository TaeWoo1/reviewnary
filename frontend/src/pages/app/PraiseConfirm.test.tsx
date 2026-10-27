// @vitest-environment jsdom
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { PraiseConfirm } from "./PraiseConfirm";
import { api } from "../../lib/apiClient";
import type { OperatorVocItem, ReviewReplyPrep, ReviewWorkView } from "../../lib/types";

vi.mock("../../lib/apiClient", () => ({
  api: {
    getCustomerOperationsHome: vi.fn(() => Promise.reject(new Error("off"))),
    getOperationsHomeStrict: vi.fn(() => Promise.reject(new Error("off"))),
    getInquiryQueueStrict: vi.fn(() => Promise.reject(new Error("off"))),
    getReviewWorkStrict: vi.fn(),
    getReviewReplyPrep: vi.fn(),
    decideReviewReplyApproval: vi.fn(),
  },
}));

const today = new Date().toISOString().slice(0, 10);
const todo = (id: string, rating: number): OperatorVocItem =>
  ({
    channelCode: "NAVER", channelNameKo: "네이버", sourceType: "REVIEW", productName: "선바로", rating, replyStatus: "PENDING",
    sourceCreatedDate: today, collectedDate: today, signalType: "REVIEW", safePreview: `리뷰 ${id}`, actionRef: `review:${id}`,
    reviewId: id, triageDisposition: "RESPONSE_NEEDED", hasReplyPreparation: true, replyWorkState: "AWAITING_APPROVAL",
    category: null, hasReportedSubmission: false,
  }) as OperatorVocItem;

const prep = (id: string, over: Partial<ReviewReplyPrep> = {}): ReviewReplyPrep =>
  ({
    actionRef: `review:${id}`, triageDisposition: "RESPONSE_NEEDED",
    draft: { version: 7, body: `답글 ${id}`, contentFingerprint: "x", fingerprintAlgorithm: "review-reply-v1", createdAt: "2026-10-27T00:00:00Z" },
    capabilities: { canSave: true, canApprove: true, canWithdraw: false, canCopy: false, canStartSubmissionRun: false },
    phraseFindings: [], approveUnavailableReason: null, ...over,
  }) as ReviewReplyPrep;

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(api.getReviewWorkStrict).mockResolvedValue({
    attentionTotal: 0, attention: [],
    committed: [{ accountId: "acc", channelCode: "NAVER", channelNameKo: "네이버", coverage: "COVERED", todo: [todo("p1", 5), todo("p2", 4), todo("low", 2), todo("ban", 5)], recentlyReported: [] }],
  } as unknown as ReviewWorkView);
  vi.mocked(api.getReviewReplyPrep).mockImplementation(async (_account, ref) => {
    const id = ref.slice("review:".length);
    return id === "ban"
      ? prep(id, { phraseFindings: [{ kind: "FORBIDDEN", phrase: "환불 불가", replacement: null, sentence: null }] })
      : prep(id);
  });
  vi.mocked(api.decideReviewReplyApproval).mockResolvedValue({ state: "APPROVED" } as never);
});

function draw() {
  return render(
    <MemoryRouter>
      <PraiseConfirm />
    </MemoryRouter>,
  );
}

describe("칭찬 리뷰 답글, 한 번에 확인 (2026-10-28)", () => {
  it("lists only ★4–5 thank-yous, each beside its reply", async () => {
    draw();
    await screen.findByText("답글 p1");
    const rows = screen.getAllByTestId("praise-row");
    expect(rows).toHaveLength(3);
    expect(screen.queryByText("리뷰 low")).toBeNull();
  });

  it("confirms the ticked ones only, each bound to the version shown, and never one with a banned phrase", async () => {
    const user = userEvent.setup();
    draw();
    await screen.findByText("답글 p2");
    await waitFor(() => expect(screen.getAllByRole("checkbox")).toHaveLength(2));
    expect(within(screen.getAllByTestId("praise-row")[2]!).getByText(/쓰지 않기로 한 표현/)).toBeTruthy();
    await user.click(screen.getByRole("checkbox", { name: /리뷰 p2/ }));
    await user.click(screen.getByRole("button", { name: "고른 1건 확정" }));
    await waitFor(() => expect(api.decideReviewReplyApproval).toHaveBeenCalledTimes(1));
    const [account, ref, body] = vi.mocked(api.decideReviewReplyApproval).mock.calls[0]!;
    expect([account, ref, body.state, body.baseVersion]).toEqual(["acc", "review:p1", "APPROVED", 7]);
    expect(await screen.findByText("확정함")).toBeTruthy();
  });
});
