// @vitest-environment jsdom
import { describe, expect, it, vi, beforeEach } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import type { ReplyHold } from "../lib/types";

import { MemoryRouter } from "react-router-dom";
import type { ReactElement } from "react";

const confirmReplyHold = vi.fn();
const getReplyHold = vi.fn();
vi.mock("../lib/apiClient", () => ({
  api: {
    confirmReplyHold: (...a: unknown[]) => confirmReplyHold(...a),
    getReplyHold: (...a: unknown[]) => getReplyHold(...a),
  },
}));

import { ReplyHoldNotice } from "./ReplyHoldNotice";

const inRouter = (ui: ReactElement) => render(<MemoryRouter>{ui}</MemoryRouter>);

const base: ReplyHold = {
  lane: "INQUIRY",
  subjectId: "w-1",
  draftVersion: 3,
  approved: false,
  codes: ["SOURCE_CHANGED", "FIGURE_UNVERIFIED"],
  needs: "CONFIRM",
  confirmed: false,
  headline: "「교환 정책」이(가) 바뀌어 다시 확인이 필요해요.",
  reasons: ["답변의 「7일」이(가) 지금도 맞는지 자료만으로는 확인할 수 없어요."],
  changed: ["교환 정책"],
  sentences: ["수령 후 7일 이내 교환 가능합니다."],
  figures: [{ figure: "7일", verdict: "FIGURE_UNVERIFIED", was: "수령 후 7일 이내 교환 가능합니다", source: "ORG_POLICY" }],
  checkedAt: "2026-11-01T00:00:00Z",
  checkId: "check-a",
  unbacked: [],
  conflicts: [],
};

const unbacked: ReplyHold = {
  ...base,
  codes: ["UNSUPPORTED_PROMISE"],
  needs: "EXCEPTION",
  headline: "등록된 정책·자료에 근거가 없는 약속이 있어요.",
  reasons: ["지금 등록된 정책·안내에는 「무료 반품」 부분의 근거가 없어요."],
  figures: [],
  unbacked: ["무료 반품"],
};

/**
 * The hold above a reply says what it is, why, and what to do (2026-11-01 review): the headline, then 「바뀐 내용 보기」
 * for the reasons, the reply's sentences and the clause each number was written from, 「답변 다시 만들기」, and — only
 * where the seller may go on past it — 「확인하고 진행」, which records the confirmation on the server.
 */
describe("ReplyHoldNotice", () => {
  beforeEach(() => {
    confirmReplyHold.mockReset();
    getReplyHold.mockReset();
  });

  it("shows the headline and the next step, and the reasons behind a fold", () => {
    inRouter(<ReplyHoldNotice hold={base} onConfirmed={() => {}} onRegenerate={() => {}} />);
    expect(screen.getByText(/교환 정책」이\(가\) 바뀌어 다시 확인이 필요해요/)).toBeInTheDocument();
    expect(screen.getByText(/확인한 기록이 남아요/)).toBeInTheDocument();
    expect(screen.queryByText(/근거였던 문장/)).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "바뀐 내용 보기" }));
    expect(screen.getByText(/근거였던 문장: 「수령 후 7일 이내 교환 가능합니다」/)).toBeInTheDocument();
    expect(screen.getByText("「수령 후 7일 이내 교환 가능합니다.」")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "답변 다시 만들기" })).toBeInTheDocument();
  });

  it("records a confirmation and hands back what the server says", async () => {
    const after = { ...base, confirmed: true };
    confirmReplyHold.mockResolvedValue(after);
    const onConfirmed = vi.fn();
    inRouter(<ReplyHoldNotice hold={base} onConfirmed={onConfirmed} />);
    fireEvent.click(screen.getByRole("button", { name: "확인하고 진행" }));
    await waitFor(() => expect(onConfirmed).toHaveBeenCalledWith(after));
    expect(confirmReplyHold).toHaveBeenCalledWith("INQUIRY", "w-1", 3, "check-a", "CONFIRM");
  });

  it("offers no plain confirmation past an unbacked promise — a ground, an edit, or an explicit exception", async () => {
    const after = { ...unbacked, confirmed: true };
    confirmReplyHold.mockResolvedValue(after);
    const onConfirmed = vi.fn();
    inRouter(<ReplyHoldNotice hold={unbacked} onConfirmed={onConfirmed} onRegenerate={() => {}} />);
    expect(screen.queryByRole("button", { name: "확인하고 진행" })).toBeNull();
    expect(screen.getByText("결정 필요")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "근거 추가하기" })).toHaveAttribute("href", "/settings/policies");
    // One press opens the decision, naming the words; only the second records it.
    fireEvent.click(screen.getByRole("button", { name: "이번 건만 예외" }));
    expect(confirmReplyHold).not.toHaveBeenCalled();
    expect(screen.getByText(/「무료 반품」은\(는\) 등록된 정책·자료에 근거가 없어요/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "이번 건만 예외로 결정" }));
    await waitFor(() => expect(onConfirmed).toHaveBeenCalledWith(after));
    expect(confirmReplyHold).toHaveBeenCalledWith("INQUIRY", "w-1", 3, "check-a", "EXCEPTION");
  });

  it("reads the hold again when the reasons changed while it was open", async () => {
    const fresh = { ...unbacked, checkId: "check-b" };
    confirmReplyHold.mockRejectedValue(
      Object.assign(new Error("409"), {
        isAxiosError: true,
        response: { status: 409, data: { message: "보고 계시던 사이 확인할 내용이 바뀌었어요." } },
      }),
    );
    getReplyHold.mockResolvedValue(fresh);
    const onConfirmed = vi.fn();
    inRouter(<ReplyHoldNotice hold={base} onConfirmed={onConfirmed} />);
    fireEvent.click(screen.getByRole("button", { name: "확인하고 진행" }));
    await waitFor(() => expect(onConfirmed).toHaveBeenCalledWith(fresh));
    expect(screen.getByRole("alert")).toHaveTextContent("확인할 내용이 바뀌었어요");
  });

  it("offers no way past what must be fixed", () => {
    inRouter(<ReplyHoldNotice hold={{ ...base, needs: "FIX", codes: ["FIGURE_CHANGED"] }} onConfirmed={() => {}} />);
    expect(screen.queryByRole("button", { name: "확인하고 진행" })).toBeNull();
    expect(screen.getByText(/확인만으로 진행할 수 없어요/)).toBeInTheDocument();
    expect(screen.getByText("고쳐야 해요")).toBeInTheDocument();
  });
});
