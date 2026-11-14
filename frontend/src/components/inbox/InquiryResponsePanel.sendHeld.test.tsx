// @vitest-environment jsdom
/**
 * Two things the 2026-11-10 rehearsal found on the inquiry screen:
 *
 * - F6: under a hold the seller has not settled, 「모의 등록」 was pressable and came back refused. The send now waits for
 *   the hold the way the copy does, and says why beside the button. The server still refuses either way.
 * - F2: after a registration the channel confirmed, the screen said 「등록하고 있어요」 beside 「답변이 등록됐어요」 until a
 *   reload. The inquiry is read again once the result is final.
 */
import { render as draw, screen, waitFor } from "@testing-library/react";
import type { ReactElement } from "react";
import { MemoryRouter } from "react-router-dom";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { InquiryResponsePanel } from "./InquiryResponsePanel";

const render = (ui: ReactElement) => draw(<MemoryRouter>{ui}</MemoryRouter>);

const getInquiryDetailStrict = vi.fn();
const getReplyHold = vi.fn();
const confirmInquiryPublish = vi.fn();

vi.mock("../../lib/apiClient", () => ({
  api: {
    getInquiryDetailStrict: (id: string) => getInquiryDetailStrict(id),
    getInquiryPublishCapability: () =>
      Promise.resolve({ executionEnabled: true, replyAdapterChannelCodes: ["COUPANG"] }),
    getReplyHold: (lane: string, id: string) => getReplyHold(lane, id),
    confirmInquiryPublish: (id: string, r: unknown) => confirmInquiryPublish(id, r),
    verifyInquiryPublish: vi.fn(),
    resumeInquiryPublish: vi.fn(),
  },
  getToken: () => null,
}));

const DRAFT = {
  version: 2, answerStatus: 0, title: "[답변] 교환 문의", comments: "합성-교환 접수했어요.", contentFingerprint: "f".repeat(64),
  fingerprintAlgorithm: "SHA-256", createdAt: "2026-11-10T00:00:00Z", authorKind: "MODEL" as const,
  modelVersion: "test-model/v1", knowledgeState: "GROUNDED" as const, knowledgeNote: null,
};

function detail(over: Record<string, unknown> = {}) {
  return {
    workItemId: "w1", inquiryId: "i1", sellerAccountId: "s1", channelId: "c1", channelCode: "COUPANG",
    channelNameKo: "쿠팡", isSecret: false, phase: "PROPOSED", status: "UNANSWERED", informStatus: null,
    title: "교환 문의", details: "합성-교환 되나요", receivedAt: "2026-11-10T00:00:00Z", proposal: null, draft: DRAFT,
    productId: "p1", productName: "합성 상품", sourceSubtype: null, answerStateProven: true, answerStateNote: null,
    draftEvidence: [], ...over,
  };
}

function hold(needs: "FIX" | "EXCEPTION" | "CONFIRM", over: Record<string, unknown> = {}) {
  return {
    lane: "INQUIRY", subjectId: "w1", draftVersion: 2, approved: false, codes: ["UNSUPPORTED_PROMISE"], needs,
    confirmed: false, headline: "다시 확인이 필요해요.", reasons: ["합성 사유"], changed: [], sentences: [], figures: [],
    checkedAt: "2026-11-10T00:00:00Z", checkId: "c".repeat(64), unbacked: [], conflicts: [], ...over,
  };
}

beforeEach(() => {
  getInquiryDetailStrict.mockResolvedValue(detail());
  getReplyHold.mockResolvedValue(null);
});

afterEach(() => {
  vi.clearAllMocks();
});

describe("the send waits for a hold the seller has not settled (F6)", () => {
  it("「고쳐야 해요」: the send is not offered as pressable, and the reason is beside it", async () => {
    getReplyHold.mockResolvedValue(hold("FIX", { codes: ["POLICY_CONFLICT"] }));
    render(<InquiryResponsePanel workItemId="w1" />);
    const send = await screen.findByRole("button", { name: /답변 보내기/ });
    await waitFor(() => expect(send).toBeDisabled());
    expect(screen.getByTestId("send-held")).toHaveTextContent("위 안내대로 고친 뒤 보낼 수 있어요.");
  });

  it("an undecided exception or confirmation waits for the decision; a decided one does not", async () => {
    getReplyHold.mockResolvedValue(hold("EXCEPTION"));
    const { unmount } = render(<InquiryResponsePanel workItemId="w1" />);
    const send = await screen.findByRole("button", { name: /답변 보내기/ });
    await waitFor(() => expect(send).toBeDisabled());
    expect(screen.getByTestId("send-held")).toHaveTextContent("결정한 뒤 보낼 수 있어요");
    unmount();

    getReplyHold.mockResolvedValue(hold("EXCEPTION", { confirmed: true }));
    render(<InquiryResponsePanel workItemId="w1" />);
    await waitFor(() => expect(getReplyHold).toHaveBeenCalled());
    const again = await screen.findByRole("button", { name: /답변 보내기/ });
    await waitFor(() => expect(again).toBeEnabled());
    expect(screen.queryByTestId("send-held")).toBeNull();
  });

  it("a send the server refuses for a hold closes the confirm and leaves the button waiting, with why", async () => {
    const user = userEvent.setup();
    confirmInquiryPublish.mockRejectedValue(Object.assign(new Error("409"), {
      isAxiosError: true,
      response: { status: 409, data: { message: "다시 확인이 필요해요. 등록된 정책은 「14일」 부분을 다르게 말하고 있어요." } },
    }));
    render(<InquiryResponsePanel workItemId="w1" />);
    await user.click(await screen.findByRole("button", { name: /답변 보내기/ }));
    getReplyHold.mockResolvedValue(hold("FIX", { codes: ["POLICY_CONFLICT"], conflicts: ["14일"] }));
    await user.click(screen.getByRole("button", { name: /확인, 등록해요/ }));

    await screen.findByText("다시 확인이 필요해 보내지 않았어요. 위의 안내를 확인해 주세요.");
    await waitFor(() => expect(screen.queryByRole("button", { name: /확인, 등록해요/ })).toBeNull());
    expect(screen.getByRole("button", { name: /답변 보내기/ })).toBeDisabled();
    expect(screen.getByTestId("send-held")).toHaveTextContent("위 안내대로 고친 뒤 보낼 수 있어요.");
    expect(confirmInquiryPublish).toHaveBeenCalledTimes(1);
  });

  it("a hold on an approved answer is the resume path's — it does not touch this button", async () => {
    getReplyHold.mockResolvedValue(hold("FIX", { approved: true }));
    render(<InquiryResponsePanel workItemId="w1" />);
    const send = await screen.findByRole("button", { name: /답변 보내기/ });
    await waitFor(() => expect(getReplyHold).toHaveBeenCalled());
    expect(send).toBeEnabled();
  });
});

describe("a registration the channel confirmed is read back at once (F2)", () => {
  it("reads the inquiry again after a COMPLETED result, and the answer is the registered one without a reload", async () => {
    const user = userEvent.setup();
    confirmInquiryPublish.mockResolvedValue({
      workItemId: "w1", phase: "COMPLETED", executionStatus: "COMPLETED", category: "COMPLETED", approvedDraftVersion: 2,
      approvedFingerprint: "f".repeat(64), providerMessageNo: "fake-1", resultCode: null, presendStateProven: true,
      presendNote: null,
    });
    render(<InquiryResponsePanel workItemId="w1" />);
    await user.click(await screen.findByRole("button", { name: /답변 보내기/ }));
    getInquiryDetailStrict.mockResolvedValue(detail({
      phase: "COMPLETED", status: "ANSWERED",
      delivery: { status: "COMPLETED", category: "COMPLETED", verified: true, observedSignal: null },
    }));
    await user.click(screen.getByRole("button", { name: /확인, 등록해요/ }));

    await screen.findByText("답변이 등록됐어요.");
    await waitFor(() => expect(getInquiryDetailStrict).toHaveBeenCalledTimes(2));
    expect(await screen.findByText("등록한 답변")).toBeInTheDocument();
  });

  it("a result that is not final reads nothing again", async () => {
    const user = userEvent.setup();
    confirmInquiryPublish.mockResolvedValue({
      workItemId: "w1", phase: "ACTION_PENDING", executionStatus: "ACTION_PENDING", category: "RETRYABLE_FAILURE",
      approvedDraftVersion: 2, approvedFingerprint: "f".repeat(64), providerMessageNo: null, resultCode: null,
      presendStateProven: true, presendNote: null,
    });
    render(<InquiryResponsePanel workItemId="w1" />);
    await user.click(await screen.findByRole("button", { name: /답변 보내기/ }));
    await user.click(screen.getByRole("button", { name: /확인, 등록해요/ }));
    await waitFor(() => expect(confirmInquiryPublish).toHaveBeenCalledTimes(1));
    expect(getInquiryDetailStrict).toHaveBeenCalledTimes(1);
  });
});

describe("the press on a LAN address — http://192.168.x.x is not a secure context (2026-11-10 same-Wi-Fi demo)", () => {
  it("sends a fresh UUID command id even where crypto.randomUUID does not exist", async () => {
    const real = globalThis.crypto;
    vi.stubGlobal("crypto", { getRandomValues: real.getRandomValues.bind(real) });
    try {
      const user = userEvent.setup();
      confirmInquiryPublish.mockResolvedValue({
        workItemId: "w1", phase: "ACTION_PENDING", executionStatus: "ACTION_PENDING", category: "PENDING",
        approvedDraftVersion: 2, approvedFingerprint: "f".repeat(64), providerMessageNo: null, resultCode: null,
        presendStateProven: true, presendNote: null,
      });
      render(<InquiryResponsePanel workItemId="w1" />);
      await user.click(await screen.findByRole("button", { name: /답변 보내기/ }));
      await user.click(screen.getByRole("button", { name: /확인, 등록해요/ }));
      await waitFor(() => expect(confirmInquiryPublish).toHaveBeenCalledTimes(1));
      const [, request] = confirmInquiryPublish.mock.calls[0]!;
      expect(request.commandId).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
    } finally {
      vi.unstubAllGlobals();
    }
  });
});
