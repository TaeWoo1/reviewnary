// @vitest-environment jsdom
/**
 * <b>A failed sentence is not lost</b> (2026-10-28). A walk-through sent one question, read 「에이전트 서비스에 연결하지
 * 못했어요」, and had neither a retry nor its words back. The thread now keeps the sentence (`failedText`) and sends it
 * again in place of its first bubble.
 */
import { describe, expect, it, vi, beforeEach } from "vitest";
import { act, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { AgentPanelProvider } from "../agentPanel";

vi.mock("./conversationClient", () => ({
  conversationClient: {
    createConversation: vi.fn(async () => ({ conversationId: "c-1", createdAt: "x" })),
    getConversation: vi.fn(),
    listConversations: vi.fn(async () => []),
    sendTurn: vi.fn(),
  },
}));
vi.mock("../auth", () => ({ useOptionalAuth: () => ({ user: { orgId: "org-a" } }) }));
vi.mock("../bridge/localAgentHint", () => ({ probeLocalAgent: vi.fn(async () => "UNKNOWN") }));

import { conversationClient } from "./conversationClient";
import { ConversationProvider, useConversation } from "./ConversationProvider";

function Probe() {
  const c = useConversation();
  return (
    <div>
      <p data-testid="turns">{c?.turns.filter((t) => t.role === "USER").map((t) => t.text).join("|") ?? ""}</p>
      <p data-testid="failed">{c?.failedText ?? "-"}</p>
      <button type="button" onClick={() => void c?.send("오늘 리뷰 뭐 들어왔어?")}>보내기</button>
      <button type="button" onClick={() => void c?.retry()}>다시</button>
    </div>
  );
}

beforeEach(() => {
  window.localStorage.clear();
  vi.clearAllMocks();
});

describe("a failed send", () => {
  it("keeps the sentence, and 다시 시도 sends it again as one bubble", async () => {
    vi.mocked(conversationClient.sendTurn).mockRejectedValueOnce(new Error("down")).mockResolvedValueOnce({
      turnId: "t1", conversationId: "c-1", role: "AGENT", message: "답이에요", artifacts: [], suggestedActions: [],
      continuation: { workingSet: null, pendingHumanAction: null, pendingPrepared: null }, status: "DONE", createdAt: "x",
    } as never);
    render(
      <MemoryRouter>
        <AgentPanelProvider>
          <ConversationProvider>
            <Probe />
          </ConversationProvider>
        </AgentPanelProvider>
      </MemoryRouter>,
    );
    await act(async () => screen.getByRole("button", { name: "보내기" }).click());
    await waitFor(() => expect(screen.getByTestId("failed")).toHaveTextContent("오늘 리뷰 뭐 들어왔어?"));
    await act(async () => screen.getByRole("button", { name: "다시" }).click());
    await waitFor(() => expect(conversationClient.sendTurn).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(screen.getByTestId("failed")).toHaveTextContent("-"));
    expect(screen.getByTestId("turns").textContent).toBe("오늘 리뷰 뭐 들어왔어?");
  });
});
