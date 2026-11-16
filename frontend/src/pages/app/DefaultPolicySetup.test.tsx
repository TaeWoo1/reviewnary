// @vitest-environment jsdom
import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen, fireEvent, waitFor, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import type { DefaultPolicyItem, DefaultPolicyOverview } from "../../lib/defaultPolicy";

const api = vi.hoisted(() => ({
  getDefaultPolicy: vi.fn(),
  saveDefaultPolicyDraft: vi.fn(),
  confirmDefaultPolicy: vi.fn(),
  unconfirmDefaultPolicy: vi.fn(),
}));
vi.mock("../../lib/apiClient", () => ({ api }));

import { DefaultPolicySetup } from "./DefaultPolicySetup";

function item(over: Partial<DefaultPolicyItem>): DefaultPolicyItem {
  return {
    key: "COURIER", group: "SHIPPING", groupLabel: "배송", label: "택배사", question: "어느 택배사로 보내나요?",
    kind: "TEXT", placeholder: "예: CJ대한통운", legal: null, shared: false, status: "EMPTY", draftNumber: null,
    draftText: null, confirmedNumber: null, confirmedText: null, sentence: null, draftSentence: null, warning: null,
    confirmedAt: null, ...over,
  };
}

function overview(items: DefaultPolicyItem[], confirmed = 0): DefaultPolicyOverview {
  return { items, confirmed, total: 12, documents: [] };
}

const CUTOFF = item({
  key: "DISPATCH_CUTOFF", label: "출고 마감 시각", kind: "SHARED", shared: true, status: "CONFIRMED",
  sentence: "평일(월~금) 오후 2시 이전 결제 주문은 당일 출고합니다.", placeholder: null,
});
const WINDOW = item({
  key: "RETURN_WINDOW", group: "RETURNS", groupLabel: "교환·반품", label: "교환·반품 신청 기간", kind: "DAYS",
  legal: "상품을 받은 날부터 7일 안에는 청약철회(반품)를 할 수 있어요.", placeholder: "예: 7",
});

function renderPage() {
  return render(
    <MemoryRouter>
      <DefaultPolicySetup />
    </MemoryRouter>,
  );
}

describe("기본 설정 질문지 (2026-11-17)", () => {
  beforeEach(() => vi.resetAllMocks());

  it("groups the questions, shows 출고 마감 from the TalkTalk guidance with no input, and the legal line as a hint", async () => {
    api.getDefaultPolicy.mockResolvedValue(overview([CUTOFF, item({}), WINDOW], 1));
    renderPage();
    await screen.findByRole("heading", { name: "배송" });
    expect(screen.getByRole("heading", { name: "교환·반품" })).toBeTruthy();
    const cutoff = screen.getByTestId("dp-row-DISPATCH_CUTOFF");
    expect(within(cutoff).queryByRole("textbox")).toBeNull();
    expect(within(cutoff).getByRole("link", { name: "배송 기준 바꾸기" }).getAttribute("href")).toBe("/settings/talktalk");
    expect(screen.getByTestId("dp-legal-RETURN_WINDOW").textContent).toContain("법정 기준");
    expect(screen.getByTestId("dp-legal-RETURN_WINDOW").textContent).toContain("이 안내는 가게 답으로 쓰지 않아요");
    expect(screen.getByTestId("dp-progress").textContent).toContain("12개 중 1개");
  });

  it("confirm saves what is on screen and confirms exactly the saved draft", async () => {
    api.getDefaultPolicy.mockResolvedValue(overview([WINDOW]));
    const saved = overview([{ ...WINDOW, status: "DRAFT", draftNumber: 7, draftText: "주말 제외" }]);
    api.saveDefaultPolicyDraft.mockResolvedValue(saved);
    api.confirmDefaultPolicy.mockResolvedValue(
      overview([{ ...WINDOW, status: "CONFIRMED", draftNumber: 7, confirmedNumber: 7, sentence: "교환·반품은 … 7일 안에" }], 1),
    );
    renderPage();
    fireEvent.change(await screen.findByLabelText("교환·반품 신청 기간"), { target: { value: "7" } });
    fireEvent.change(screen.getByLabelText("교환·반품 신청 기간 덧붙일 말"), { target: { value: "주말 제외" } });
    fireEvent.click(screen.getByTestId("dp-confirm-RETURN_WINDOW"));
    await waitFor(() => expect(api.confirmDefaultPolicy).toHaveBeenCalled());
    expect(api.saveDefaultPolicyDraft).toHaveBeenCalledWith("RETURN_WINDOW", { number: 7, text: "주말 제외" });
    expect(api.confirmDefaultPolicy).toHaveBeenCalledWith("RETURN_WINDOW", { number: 7, text: "주말 제외" });
    expect((await screen.findByRole("status")).textContent).toContain("확정했어요");
  });

  it("a non-number is refused on screen and nothing is sent; an empty answer cannot be confirmed", async () => {
    api.getDefaultPolicy.mockResolvedValue(overview([WINDOW]));
    renderPage();
    const confirm = await screen.findByTestId("dp-confirm-RETURN_WINDOW");
    expect((confirm as HTMLButtonElement).disabled).toBe(true);
    fireEvent.change(screen.getByLabelText("교환·반품 신청 기간"), { target: { value: "일주일" } });
    fireEvent.click(screen.getByRole("button", { name: "저장만 하기" }));
    expect((await screen.findByRole("alert")).textContent).toContain("숫자만");
    expect(api.saveDefaultPolicyDraft).not.toHaveBeenCalled();
  });

  it("the server's refusal is shown as it says it", async () => {
    api.getDefaultPolicy.mockResolvedValue(overview([item({})]));
    api.saveDefaultPolicyDraft.mockRejectedValue(
      Object.assign(new Error("400"), { isAxiosError: true, response: { data: { message: "「【확인」 표시는 답에 쓸 수 없어요." } } }),
    );
    renderPage();
    fireEvent.change(await screen.findByLabelText("택배사"), { target: { value: "【확인: 택배사】" } });
    fireEvent.click(screen.getByRole("button", { name: "저장만 하기" }));
    expect((await screen.findByRole("alert")).textContent).toContain("【확인");
  });
});
