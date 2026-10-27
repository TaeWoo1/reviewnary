// @vitest-environment jsdom
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { SafetyMarkNote, markedSentence } from "./SafetyMarkNote";
import { api } from "../../lib/apiClient";
import { expectNoAxeViolations } from "../../test/axe";

vi.mock("../../lib/apiClient", () => ({ api: { reportWrongSafetyMark: vi.fn() } }));

// A review's mark on its own page (mockup ②, 2026-10-25): the label, where the review says it, and an honest line.

describe("SafetyMarkNote", () => {
  it("marks the expression in the review's own sentence, however it was spaced", () => {
    expect(markedSentence("선반은 튼튼해요. 조립하다가 아이가 손을 베 였어요. 처리 부탁드려요.", "베였")).toEqual({
      before: "조립하다가 아이가 손을 ",
      hit: "베 였",
      after: "어요.",
    });
    expect(markedSentence("다른 말이에요.", "베였")).toBeNull();
  });

  it("says it is the review's words, and keeps the seller's word without changing the mark", async () => {
    vi.mocked(api.reportWrongSafetyMark).mockResolvedValue(undefined);
    const { container } = render(
      <SafetyMarkNote
        reviewId="r-1"
        mark={{ kind: "INJURY", reading: "OCCURRENCE", term: "베였" }}
        body="<p>조립하다가 아이가 손을 베였어요.</p>"
      />,
    );

    expect(screen.getByText("부상 언급")).toBeInTheDocument();
    expect(screen.getByText("베였").tagName).toBe("MARK");
    expect(screen.getByTestId("safety-mark")).toHaveTextContent("리뷰 문구 기준 표시 · 사실 여부 미확인");
    await expectNoAxeViolations(container);

    await userEvent.click(screen.getByRole("button", { name: "표시가 맞지 않음" }));
    expect(api.reportWrongSafetyMark).toHaveBeenCalledWith("r-1");
    expect(await screen.findByRole("status")).toHaveTextContent("의견 남김");
    expect(screen.getByText("부상 언급")).toBeInTheDocument();
  });

  it("reads a worry as a worry, whatever it is about", () => {
    render(<SafetyMarkNote reviewId="r-2" mark={{ kind: "INJURY", reading: "WORRY", term: "다칠" }} body="아이가 다칠까 걱정돼요" />);
    expect(screen.getByText("걱정 언급")).toBeInTheDocument();
  });
});
