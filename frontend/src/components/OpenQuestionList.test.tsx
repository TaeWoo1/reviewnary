// @vitest-environment jsdom
import { describe, it, expect, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import { OpenQuestionList } from "./OpenQuestionList";

const listOpenQuestions = vi.fn();
vi.mock("../lib/apiClient", () => ({
  api: {
    listOpenQuestions: (...a: unknown[]) => listOpenQuestions(...a),
    handleOpenQuestion: vi.fn(),
  },
}));

const q = (id: string, state: string, required = true) => ({
  id, question: `질문 ${id}`, required, state, resolution: state === "OPEN" ? null : "REJUDGED", note: null,
  createdAt: "2026-11-01T00:00:00Z", closedAt: null,
});

describe("OpenQuestionList (2026-11-01 tenth review)", () => {
  it("tells the inquiry how many required questions still hold the send, and says why on screen", async () => {
    listOpenQuestions.mockResolvedValue([q("a", "OPEN"), q("b", "OPEN", false), q("c", "SUPERSEDED")]);
    const onChange = vi.fn();
    render(<OpenQuestionList lane="INQUIRY" subjectId="w-1" onChange={onChange} />);
    await waitFor(() => expect(onChange).toHaveBeenCalledWith(1));
    expect(listOpenQuestions).toHaveBeenCalledWith("INQUIRY", "w-1");
    expect(screen.getByTestId("open-questions")).toHaveTextContent("답하지 못한 질문 2개");
    expect(screen.getByTestId("open-questions")).toHaveTextContent("질문마다 처리해야 보낼 수 있어요");
    expect(screen.queryByTestId("open-questions-closed")).toBeNull();
    expect(screen.getByTestId("open-questions-history")).toHaveTextContent("질문 c · 다시 판단해 빠짐");
  });

  it("names a question the seller's completed action made moot as settled, never as answered (2026-11-06)", async () => {
    listOpenQuestions.mockResolvedValue([
      { ...q("d", "SUPERSEDED"), resolution: "SETTLED_BY_ACTION", basisRef: "seller-check:1b2c" },
    ]);
    const onChange = vi.fn();
    render(<OpenQuestionList lane="INQUIRY" subjectId="w-3" onChange={onChange} />);
    await waitFor(() => expect(onChange).toHaveBeenCalledWith(0));
    const closed = screen.getByTestId("open-questions-closed");
    expect(closed).toHaveTextContent("질문 d · 조치 완료로 판단 불필요 (판매자 확인 기록 기준)");
    expect(closed).not.toHaveTextContent("답을 덧붙임");
    expect(screen.queryByTestId("open-question")).toBeNull();
  });

  it("a question still open is never also listed as handled: its earlier wording is folded history (2026-11-06)", async () => {
    listOpenQuestions.mockResolvedValue([
      { ...q("e", "OPEN"), question: "교환 배송비는 누가 내는지 — 배송비 부담 기준" },
      { ...q("f", "SUPERSEDED"), question: "교환 배송비는 누가 내는지 — 배송비 부담 기준 · 자료로 답할 수 있는데 초안이 다루지 않았어요" },
      { ...q("g", "HANDLED"), question: "세탁 방법", resolution: "NOT_NEEDED" },
    ]);
    const onChange = vi.fn();
    render(<OpenQuestionList lane="INQUIRY" subjectId="w-4" onChange={onChange} />);
    await waitFor(() => expect(onChange).toHaveBeenCalledWith(1));
    expect(screen.getAllByTestId("open-question")).toHaveLength(1);
    const handled = screen.getByTestId("open-questions-closed");
    expect(handled).toHaveTextContent("처리한 질문 1개");
    expect(handled).not.toHaveTextContent("배송비");
    const history = screen.getByTestId("open-questions-history");
    expect(history).toHaveTextContent("판단 변경 이력 1개");
    expect(history).toHaveTextContent("지금 남은 질문으로 바뀜");
    expect(history.hasAttribute("open")).toBe(false);
  });

  it("draws nothing when nothing was left", async () => {
    listOpenQuestions.mockResolvedValue([]);
    const { container } = render(<OpenQuestionList lane="INQUIRY" subjectId="w-2" />);
    await waitFor(() => expect(listOpenQuestions).toHaveBeenCalledWith("INQUIRY", "w-2"));
    expect(container).toBeEmptyDOMElement();
  });
});
