// @vitest-environment jsdom
import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { AgentHandoffs } from "./AgentHandoffs";

const api = vi.hoisted(() => ({ getReplyHandoffsStrict: vi.fn() }));
vi.mock("../../lib/apiClient", () => ({ api }));

describe("AI가 사람에게 넘긴 리뷰 (review 2026-10-28)", () => {
  beforeEach(() => vi.resetAllMocks());

  it("lists what the agent handed off, a harm first, with the reporting note and a link to the review", async () => {
    api.getReplyHandoffsStrict.mockResolvedValue([
      { reviewId: "r-2", category: "R", reason: "STILL_FAILS", at: null, rating: 2, preview: "하루 만에 떨어졌어요" },
      { reviewId: "r-1", category: "S", reason: "CATEGORY_S", at: null, rating: 1, preview: "화상을 입었어요", earlierDraft: true },
    ]);
    render(<MemoryRouter><AgentHandoffs /></MemoryRouter>);
    const section = await screen.findByRole("region", { name: "AI가 사람에게 넘긴 리뷰" });
    const links = within(section).getAllByRole("link");
    expect(links.map((a) => a.getAttribute("href"))).toEqual(["/reviews/reply/r-1", "/reviews/reply/r-2"]);
    expect(links[0]).toHaveTextContent("안전 관련");
    expect(links[0]).toHaveTextContent("넘기기 전 초안");
    expect(links[1]).toHaveTextContent("직접 작성 필요");
    expect(links[1]).not.toHaveTextContent("초안");
    // The duty is said once, under the harms — not inside every row.
    expect(within(section).getByTestId("handoff-duty")).toHaveTextContent("법정 보고 의무가 있을 수 있어");
    expect(links[0]).not.toHaveTextContent("법정 보고 의무");
  });

  it("the Home stays short: harms on their own lines, one duty sentence, everything else folded with its notes kept (2026-11-10)", async () => {
    const harm = (id: string) => ({ reviewId: id, category: "S", reason: "CATEGORY_S", at: null, rating: 1, preview: `합성-다침 ${id}`, points: ["부상"] });
    api.getReplyHandoffsStrict.mockResolvedValue([
      harm("r-1"),
      harm("r-2"),
      { reviewId: "r-3", category: "D", reason: "KNOWLEDGE_MISSING", at: null, rating: 3, preview: "합성-질문" },
      { reviewId: "r-4", category: null, reason: "UNSURE_AMBIGUOUS", at: null, rating: 3, preview: "합성-모호" },
    ]);
    render(<MemoryRouter><AgentHandoffs /></MemoryRouter>);
    const section = await screen.findByRole("region", { name: "AI가 사람에게 넘긴 리뷰" });
    expect(section).toHaveTextContent("4건");
    const duty = within(section).getByTestId("handoff-duty");
    expect(duty.textContent!.match(/법정 보고 의무/g)).toHaveLength(1);
    const rest = within(section).getByTestId("handoff-rest");
    expect(rest).not.toHaveAttribute("open");
    expect(rest.querySelector("summary")).toHaveTextContent("다른 이유로 넘긴 리뷰 2건근거 부족 1 · 표현이 모호함 1");
    // Folded, not dropped: every row still opens its review and keeps its note behind the fold.
    expect(within(section).getAllByRole("link", { hidden: true }).map((a) => a.getAttribute("href"))).toEqual(
      ["/reviews/reply/r-1", "/reviews/reply/r-2", "/reviews/reply/r-3", "/reviews/reply/r-4"],
    );
    expect(rest).toHaveTextContent("문제없다는 뜻이 아니니");
  });

  it("「can't tell」 and a question with no fact say what the seller has to do", async () => {
    api.getReplyHandoffsStrict.mockResolvedValue([
      { reviewId: "r-3", category: null, reason: "UNSURE_AMBIGUOUS", at: null, rating: 3, preview: "음…" },
      { reviewId: "r-4", category: "D", reason: "QUESTION_NO_FACTS", at: null, rating: 2, preview: "세탁은 어떻게 하나요",
        points: ["색이 사진과 다름", "세탁 방법 질문"] },
    ]);
    render(<MemoryRouter><AgentHandoffs /></MemoryRouter>);
    const section = await screen.findByRole("region", { name: "AI가 사람에게 넘긴 리뷰" });
    expect(section).toHaveTextContent("표현이 모호함");
    expect(section).toHaveTextContent("문제없다는 뜻이 아니니");
    expect(section).toHaveTextContent("답할 사실 없음");
    expect(section).toHaveTextContent("AI가 읽은 점: 색이 사진과 다름 · 세탁 방법 질문");
  });

  it("a draft that still failed says what was blocked, not only that it failed (2026-10-31)", async () => {
    api.getReplyHandoffsStrict.mockResolvedValue([
      { reviewId: "r-5", category: "M", reason: "STILL_FAILS", at: null, rating: 3, preview: "처음엔 잘 안 나왔어요",
        unmet: ["등록된 정책에 없는 약속(교환·반품·보상·날짜)으로 읽힐 표현을 확인하지 못했어요."] },
    ]);
    render(<MemoryRouter><AgentHandoffs /></MemoryRouter>);
    const section = await screen.findByRole("region", { name: "AI가 사람에게 넘긴 리뷰" });
    expect(section).toHaveTextContent("직접 작성 필요");
    expect(section).toHaveTextContent("약속(교환·반품·보상·날짜)으로 읽힐 표현을 확인하지 못했어요");
    expect(section).not.toHaveTextContent("AI가 기준에 맞는 답글을 쓰지 못했어요");
  });

  it("nothing handed off — or a failed read — draws nothing", async () => {
    api.getReplyHandoffsStrict.mockResolvedValue([]);
    const { unmount } = render(<MemoryRouter><AgentHandoffs /></MemoryRouter>);
    await new Promise((r) => setTimeout(r, 0));
    expect(screen.queryByRole("region", { name: "AI가 사람에게 넘긴 리뷰" })).toBeNull();
    unmount();
    api.getReplyHandoffsStrict.mockRejectedValue(new Error("down"));
    render(<MemoryRouter><AgentHandoffs /></MemoryRouter>);
    await new Promise((r) => setTimeout(r, 0));
    expect(screen.queryByRole("region", { name: "AI가 사람에게 넘긴 리뷰" })).toBeNull();
  });
});
