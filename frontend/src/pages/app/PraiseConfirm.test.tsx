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
    handOverReviewReply: vi.fn(),
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

  /**
   * 2026-10-28 (owner): on a PC linked to the delegate lane the page's own press is 「답글 등록 요청」. Approving on the
   * copy road instead would take every draft back from the delegate's phone (`yieldToApprovalHere`).
   */
  it("hands a reply over to the delegate where the lane holds it, and never approves it on the copy road", async () => {
    const lane = { taskState: "AWAITING_APPROVAL", editedOnMobile: false, canSend: true, canCancel: false };
    vi.mocked(api.getReviewReplyPrep).mockImplementation(async (_account, ref) => {
      const id = ref.slice("review:".length);
      if (id === "ban") return prep(id, { phraseFindings: [{ kind: "FORBIDDEN", phrase: "환불 불가", replacement: null, sentence: null }] });
      if (id === "p1") return prep(id, { delegation: lane });
      if (id === "p2") return prep(id, { delegation: { ...lane, setAside: { version: 6, body: "PC 문장", writtenAt: null } } });
      return prep(id);
    });
    vi.mocked(api.handOverReviewReply).mockResolvedValue({} as never);
    const user = userEvent.setup();
    draw();
    await screen.findByText("답글 p1");
    await waitFor(() => expect(screen.getAllByRole("checkbox")).toHaveLength(1));
    // The one whose words differ between phone and PC is opened on its own, not decided here.
    expect(within(screen.getAllByTestId("praise-row")[1]!).getByText(/휴대폰과 PC의 문장이 달라요/)).toBeTruthy();
    await user.click(screen.getByRole("button", { name: "고른 1건 등록 요청" }));
    await waitFor(() => expect(api.handOverReviewReply).toHaveBeenCalledWith("p1", 7));
    expect(api.decideReviewReplyApproval).not.toHaveBeenCalled();
    expect(await screen.findByText("요청함")).toBeTruthy();
  });

  it("says both roads when the list holds both", async () => {
    vi.mocked(api.getReviewReplyPrep).mockImplementation(async (_account, ref) => {
      const id = ref.slice("review:".length);
      return id === "p1"
        ? prep(id, { delegation: { taskState: null, editedOnMobile: false, canSend: true, canCancel: false } })
        : prep(id);
    });
    draw();
    await screen.findByText("답글 p2");
    await waitFor(() => expect(screen.getAllByRole("checkbox")).toHaveLength(3));
    expect(screen.getByRole("status")).toHaveTextContent("담당자 등록 요청 1건 · 직접 확정 2건");
    expect(screen.getByRole("button", { name: "고른 3건 처리" })).toBeTruthy();
  });
});
