// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";
import { ConversationProvider } from "../../lib/conversation/ConversationProvider";
import { AgentPanelProvider } from "../../lib/agentPanel";
import { ConversationMenu } from "./ConversationMenu";
import { agentTurn } from "../../test/conversationFixtures";

// The provider namespaces its remembered-conversation pointer by the signed-in org (§0).
vi.mock("../../lib/auth", () => ({ useOptionalAuth: () => ({ user: { orgId: "org-t" } }) }));
vi.mock("../../lib/conversation/conversationClient", () => ({
  conversationClient: {
    createConversation: vi.fn(async () => ({ conversationId: "c-new", createdAt: "x" })),
    getConversation: vi.fn(),
    listConversations: vi.fn(async () => [
      { conversationId: "c-1", headline: "지난 리뷰 확인", turnCount: 4, updatedAt: new Date().toISOString(), createdAt: "x" },
      { conversationId: "c-2", headline: null, turnCount: 1, updatedAt: new Date().toISOString(), createdAt: "x" },
    ]),
    sendTurn: vi.fn(),
  },
}));
import { conversationClient } from "../../lib/conversation/conversationClient";

function Where() {
  return <p data-testid="where">{useLocation().pathname}</p>;
}
function shell(path = "/reviews") {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AgentPanelProvider>
        <ConversationProvider>
          <ConversationMenu />
          <Routes><Route path="*" element={<Where />} /></Routes>
        </ConversationProvider>
      </AgentPanelProvider>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  window.localStorage.clear();
  vi.mocked(conversationClient.getConversation).mockReset();
  vi.mocked(conversationClient.listConversations).mockClear();
});

/**
 * <b>Re-pointed, not rewritten</b> (product-owner decision, 2026-10-01). These three guarantees were
 * written about the rail's 「대화」 section, which no longer exists: 새 대화 and 지난 대화 moved onto the
 * composer, which is the command surface and the one surface that is identical on desktop and
 * mobile. Every assertion below is the SAME guarantee — the current thread is marked, an earlier
 * thread opens and lands on the home, 새 대화 clears the pointer, and the list can be shown and
 * hidden — asserted where the control now is. The collapse chevron became the menu's own toggle,
 * which is why that test presses 「대화 메뉴」.
 */
describe("the composer's conversation menu", () => {
  it("lists the threads with the current one marked, and 「새 대화」 is an icon control that goes home", async () => {
    window.localStorage.setItem("reviewnary.conversation.current.org-t", "c-1");
    vi.mocked(conversationClient.getConversation).mockResolvedValue({ conversationId: "c-1", createdAt: "x", updatedAt: "x", turns: [agentTurn({ conversationId: "c-1" })], workingSet: null, pendingHumanAction: null, pendingPrepared: null });
    shell();
    await userEvent.click(screen.getByRole("button", { name: "대화 메뉴" }));
    const current = await screen.findByRole("button", { name: /지난 리뷰 확인/ });
    await waitFor(() => expect(current).toHaveAttribute("aria-current", "true"));
    expect(screen.getByRole("button", { name: /제목 없는 대화/ })).not.toHaveAttribute("aria-current");
    await userEvent.click(screen.getByRole("button", { name: "새 대화" }));
    expect(screen.getByTestId("where")).toHaveTextContent("/");
    expect(window.localStorage.getItem("reviewnary.conversation.current.org-t")).toBeNull();
  });

  it("opens an earlier thread from the sidebar and lands on the home", async () => {
    vi.mocked(conversationClient.getConversation).mockResolvedValue({ conversationId: "c-1", createdAt: "x", updatedAt: "x", turns: [agentTurn({ conversationId: "c-1", message: "지난 답변입니다." })], workingSet: null, pendingHumanAction: null, pendingPrepared: null });
    shell();
    await userEvent.click(screen.getByRole("button", { name: "대화 메뉴" }));
    await userEvent.click(await screen.findByRole("button", { name: /지난 리뷰 확인/ }));
    await waitFor(() => expect(window.localStorage.getItem("reviewnary.conversation.current.org-t")).toBe("c-1"));
    expect(screen.getByTestId("where")).toHaveTextContent("/");
  });

  it("shows and hides the list — the collapse chevron's guarantee, now the menu's own toggle", async () => {
    shell();
    // Closed at rest: the box is the subject, and the history is one press away rather than always up.
    expect(screen.queryByRole("list", { name: "지난 대화" })).toBeNull();
    await userEvent.click(screen.getByRole("button", { name: "대화 메뉴", expanded: false }));
    expect(await screen.findByRole("list", { name: "지난 대화" })).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "대화 메뉴", expanded: true }));
    await waitFor(() => expect(screen.queryByRole("list", { name: "지난 대화" })).toBeNull());
  });

  it("reads no history until it is opened — the rail fetched eight summaries on every screen", async () => {
    shell();
    expect(conversationClient.listConversations).not.toHaveBeenCalled();
    await userEvent.click(screen.getByRole("button", { name: "대화 메뉴" }));
    await waitFor(() => expect(conversationClient.listConversations).toHaveBeenCalled());
  });
});
