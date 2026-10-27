// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, within, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { expectNoAxeViolations } from "../../test/axe";
import { ReviewGuidance } from "./ReviewGuidance";
import type { ReviewGuidanceOption, ReviewGuidanceOptions } from "../../lib/types";

const getReviewGuidance = vi.fn();
const chooseReviewGuidance = vi.fn();
const clearReviewGuidance = vi.fn();
vi.mock("../../lib/apiClient", () => ({
  api: {
    getReviewGuidance: (...a: unknown[]) => getReviewGuidance(...a),
    chooseReviewGuidance: (...a: unknown[]) => chooseReviewGuidance(...a),
    clearReviewGuidance: (...a: unknown[]) => clearReviewGuidance(...a),
  },
}));

const ADHESION: ReviewGuidanceOption = {
  sourceId: "s-adhesion",
  scope: "PRODUCT",
  title: "실크벽지 부착",
  snippet: "실크벽지에서는 폼 양면테이프를 덧대 주세요.",
  state: "PHRASE",
  phrase: "떨어져요",
};
const CUTTING: ReviewGuidanceOption = {
  sourceId: "s-cutting",
  scope: "PRODUCT",
  title: "자르기",
  snippet: "커터칼로 여러 번 그어 자르세요.",
  state: null,
  phrase: null,
};
const of = (o: Partial<ReviewGuidanceOptions>): ReviewGuidanceOptions => ({
  attached: [],
  rejected: [],
  pickable: [],
  ...o,
});

beforeEach(() => {
  getReviewGuidance.mockReset().mockResolvedValue(of({ attached: [ADHESION], pickable: [CUTTING] }));
  chooseReviewGuidance.mockReset();
  clearReviewGuidance.mockReset();
});

function draw() {
  return render(<ReviewGuidance accountId="a-1" actionRef="review:r-1" />);
}

describe("이 리뷰에 맞는 안내", () => {
  it("a guidance the customer's words attached says so and that nobody has confirmed it", async () => {
    draw();
    const box = await screen.findByTestId("review-guidance");
    expect(box).toHaveTextContent("실크벽지 부착");
    expect(box).toHaveTextContent("고객 표현 「떨어져요」로 찾음 · 확인 전");
    expect(getReviewGuidance).toHaveBeenCalledWith("a-1", "review:r-1");
  });

  it("맞아요 makes it the seller's; the draft on screen is untouched and the next one follows", async () => {
    chooseReviewGuidance.mockResolvedValue(of({ attached: [{ ...ADHESION, state: "CONFIRMED" }], pickable: [CUTTING] }));
    draw();
    await userEvent.click(await screen.findByRole("button", { name: "맞아요" }));
    expect(chooseReviewGuidance).toHaveBeenCalledWith("a-1", "review:r-1", "s-adhesion", "CONFIRMED");
    expect(await screen.findByText("맞다고 확인함")).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("「다시 준비하기」를 누르면 이 안내대로 다시 씁니다");
  });

  it("아니에요 keeps it out of this review, and 되돌리기 brings it back", async () => {
    chooseReviewGuidance.mockResolvedValue(
      of({ rejected: [{ ...ADHESION, state: "REJECTED", phrase: null }], pickable: [CUTTING] }),
    );
    clearReviewGuidance.mockResolvedValue(of({ attached: [ADHESION], pickable: [CUTTING] }));
    draw();
    await userEvent.click(await screen.findByRole("button", { name: "아니에요" }));
    expect(chooseReviewGuidance).toHaveBeenCalledWith("a-1", "review:r-1", "s-adhesion", "REJECTED");
    expect(await screen.findByText("아직 붙은 안내가 없습니다. 맞는 안내가 있으면 골라 주세요.")).toBeInTheDocument();
    const fold = screen.getByRole("button", { name: /이 리뷰에서 뺀 안내 1/ });
    await userEvent.click(fold);
    await userEvent.click(screen.getByRole("button", { name: "되돌리기" }));
    expect(clearReviewGuidance).toHaveBeenCalledWith("a-1", "review:r-1", "s-adhesion");
    expect(await screen.findByText("고객 표현 「떨어져요」로 찾음 · 확인 전")).toBeInTheDocument();
  });

  it("a guidance the customer's words never reached is picked from the list", async () => {
    chooseReviewGuidance.mockResolvedValue(of({ attached: [ADHESION, { ...CUTTING, state: "PICKED" }] }));
    draw();
    await userEvent.click(await screen.findByRole("button", { name: "안내 고르기" }));
    const list = screen.getByRole("list", { name: "고를 수 있는 안내" });
    expect(within(list).getByText("자르기")).toBeInTheDocument();
    await userEvent.click(within(list).getByRole("button", { name: "고르기" }));
    expect(chooseReviewGuidance).toHaveBeenCalledWith("a-1", "review:r-1", "s-cutting", "PICKED");
    expect(await screen.findByText("직접 고름")).toBeInTheDocument();
    // Nothing left to pick, so there is no picker to open.
    await waitFor(() => expect(screen.queryByRole("button", { name: /안내 고르기|닫기/ })).toBeNull());
  });

  it("a company with no registered guidance sees no box at all", async () => {
    getReviewGuidance.mockResolvedValue(of({}));
    const { container } = draw();
    await waitFor(() => expect(getReviewGuidance).toHaveBeenCalled());
    expect(container).toBeEmptyDOMElement();
  });

  it("a failed read says so instead of an empty box", async () => {
    getReviewGuidance.mockRejectedValue(new Error("down"));
    draw();
    expect(await screen.findByText("이 리뷰에 맞는 안내를 불러오지 못했습니다.")).toBeInTheDocument();
  });

  it("has no accessibility violations", async () => {
    const { container } = draw();
    await screen.findByTestId("review-guidance");
    await userEvent.click(screen.getByRole("button", { name: "안내 고르기" }));
    await expectNoAxeViolations(container);
  });
});
