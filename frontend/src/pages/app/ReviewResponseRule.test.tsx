// @vitest-environment jsdom
import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ReviewResponseRule } from "./ReviewResponseRule";

const api = vi.hoisted(() => ({ getReviewResponseRule: vi.fn(), saveReviewResponseRule: vi.fn() }));
vi.mock("../../lib/apiClient", () => ({ api }));

describe("리뷰 응대 기준 — one choice per band (owner, 2026-10-28)", () => {
  beforeEach(() => {
    vi.resetAllMocks();
    api.getReviewResponseRule.mockResolvedValue({ low: "AHEAD", mid: "AHEAD", high: "ISSUES_ONLY", customized: false });
    api.saveReviewResponseRule.mockImplementation(async (rule) => ({ ...rule, customized: true }));
  });

  it("shows the three bands with the defaults and says nothing goes out before approval", async () => {
    render(<ReviewResponseRule />);
    const list = await screen.findByRole("list", { name: "리뷰 응대 기준" });
    expect(within(list).getAllByRole("radiogroup")).toHaveLength(3);
    expect(within(list).getAllByRole("radio", { name: "미리 쓰기", checked: true })).toHaveLength(2);
    // ★4–5 praise is not drafted ahead by default (owner, 2026-10-28) — only a ★4–5 with a problem or a question.
    const high = within(list).getByRole("radiogroup", { name: "별점 4~5점 초안" });
    expect(within(high).getByRole("radio", { name: "필요할 때만" })).toHaveAttribute("aria-checked", "true");
    // One judge for every rating, so every band offers 「필요할 때만」.
    const low = within(list).getByRole("radiogroup", { name: "별점 1~2점 초안" });
    expect(within(low).getByRole("radio", { name: "필요할 때만" })).toBeInTheDocument();
    expect(screen.getByText(/별점과 상관없이 모든 리뷰를 같은 기준으로/)).toBeInTheDocument();
    expect(screen.getByText(/승인하기 전까지 고객에게 나가지 않아요/)).toBeInTheDocument();
    expect(screen.getByText(/하루 AI 사용량의 절반까지만/)).toBeInTheDocument();
    // Complaints first, and praise capped below the half (2026-10-28).
    expect(screen.getByText(/응대가 필요하다고 본 리뷰부터/)).toBeInTheDocument();
    expect(screen.getByText(/감사 인사는 4분의 1까지만/)).toBeInTheDocument();
  });

  it("saves the band the seller changed, and only that band", async () => {
    const user = userEvent.setup();
    render(<ReviewResponseRule />);
    const high = await screen.findByRole("radiogroup", { name: "별점 4~5점 초안" });
    await user.click(within(high).getByRole("radio", { name: "누를 때 쓰기" }));
    await user.click(screen.getByRole("button", { name: "저장" }));

    expect(api.saveReviewResponseRule).toHaveBeenCalledWith({ low: "AHEAD", mid: "AHEAD", high: "ON_REQUEST" });
    // …and 「미리 쓰기」 for ★4–5 is still offered for a seller who wants their thank-yous written too.
    await user.click(within(high).getByRole("radio", { name: "미리 쓰기" }));
    await user.click(screen.getByRole("button", { name: "저장" }));
    expect(api.saveReviewResponseRule).toHaveBeenLastCalledWith({ low: "AHEAD", mid: "AHEAD", high: "AHEAD" });
    expect(await screen.findByRole("status")).toHaveTextContent("다음 자동 확인부터 반영돼요");
  });

  it("a rule that cannot be read is said, not guessed", async () => {
    api.getReviewResponseRule.mockRejectedValue(new Error("down"));
    render(<ReviewResponseRule />);
    expect(await screen.findByText("응대 기준을 불러오지 못했어요.")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "저장" })).toBeNull();
  });
});
