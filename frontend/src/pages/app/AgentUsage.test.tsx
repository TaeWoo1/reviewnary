// @vitest-environment jsdom
import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen, within } from "@testing-library/react";
import { AgentUsage } from "./AgentUsage";

const api = vi.hoisted(() => ({ getAgentUsageStrict: vi.fn(), getReplyOutcomesStrict: vi.fn() }));
vi.mock("../../lib/apiClient", () => ({ api }));

const row = (step: string, trigger: string, actor: string, calls: number, runs: number) => ({
  kind: "REPLY", step, trigger, actor, calls, runs, elapsedMs: calls * 2000, inputTokens: 1000, outputTokens: 100,
});

describe("AI 사용량 — a day by step and trigger (owner, 2026-10-28)", () => {
  beforeEach(() => vi.resetAllMocks());

  it("lists each step and trigger in the seller's words, with the per-run average", async () => {
    api.getAgentUsageStrict.mockResolvedValue({
      date: "2026-10-28", enabled: true, llmCallsLimit: 200, sellerAndRunCalls: 9, itemsLimit: 300, sellerItems: 3,
      automaticCallsLimit: 140, automaticCalls: 6,
      rows: [row("CLASSIFY", "INGEST", "RUN", 3, 3), row("DRAFT", "INGEST", "RUN", 3, 3), row("CRITIQUE", "INGEST", "RUN", 3, 3)],
    });
    render(<AgentUsage />);
    const table = await screen.findByRole("table", { name: "AI 사용량" });
    expect(within(table).getAllByText("가져온 직후")).toHaveLength(3);
    expect(within(table).getByText("판단")).toBeInTheDocument();
    expect(within(table).getByText("검토")).toBeInTheDocument();
    expect(within(table).getAllByText("자동 실행")).toHaveLength(3);
    // Nine calls over three runs (each run judges once).
    expect(screen.getByTestId("usage-summary")).toHaveTextContent("실행당 평균 3.0번");
    expect(screen.getByTestId("usage-summary")).toHaveTextContent("실제 AI 호출 9 / 200번 (자동 판단·검토·재시도 포함)");
    expect(screen.getByTestId("usage-summary")).toHaveTextContent("판매자가 맡긴 일 3 / 300건");
    expect(screen.getByTestId("usage-summary")).toHaveTextContent("그중 자동 작업 6 / 140번 (나머지 60번은 판매자 요청용)");
  });

  it("a day without calls says so; a failed read says so", async () => {
    api.getAgentUsageStrict.mockResolvedValue({ date: "2026-10-28", enabled: true, llmCallsLimit: 200, sellerAndRunCalls: 0, rows: [] });
    const { unmount } = render(<AgentUsage />);
    expect(await screen.findByText("이 날은 AI를 쓰지 않았어요.")).toBeInTheDocument();
    unmount();
    api.getAgentUsageStrict.mockRejectedValue(new Error("down"));
    render(<AgentUsage />);
    expect(await screen.findByText("사용량을 불러오지 못했어요.")).toBeInTheDocument();
  });

  it("the agent's outcomes: holds, approved as written, and why it handed over", async () => {
    api.getAgentUsageStrict.mockResolvedValue({ date: "2026-10-28", enabled: true, llmCallsLimit: 200, sellerAndRunCalls: 0, rows: [] });
    api.getReplyOutcomesStrict.mockResolvedValue({
      days: 7, runs: 10, byOutcome: { SAVED: 6, HANDOFF: 3, FAILED: 1 }, handoffReasons: { CATEGORY_S: 1, KNOWLEDGE_MISSING: 2 },
      saved: 6, approvedAsWritten: 3, approvedAfterEdit: 1, approvedAfterRewrite: 0, waiting: 2, medianMinutesToApproval: 12,
    });
    render(<AgentUsage />);
    const outcomes = await screen.findByTestId("agent-outcomes");
    expect(outcomes).toHaveTextContent("사람에게 넘김 3 (30%)");
    expect(outcomes).toHaveTextContent("AI 문장 그대로 승인 3 (75%)");
    expect(outcomes).toHaveTextContent("AI가 다시 쓴 문장 승인 0");
    expect(outcomes).toHaveTextContent("보통 12분");
    expect(outcomes).toHaveTextContent("근거 부족 2");
  });
});
