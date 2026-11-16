// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { TalkTalkWorkspace } from "./TalkTalkWorkspace";
import { TalkTalkHomeLine } from "../../components/talktalk/TalkTalkHomeLine";
import { decisionKey, sendAbility, type ConversationDetail, type TalkTalkStatus } from "../../lib/talktalk";

/**
 * 톡톡 상담 화면 (docs/inquiry/talktalk_chat_lane_v1.md §7, UI review): the screen re-reads while visible, so a new
 * customer line and a QUEUED send's result appear without a reload; an unsaved sentence is never overwritten; a
 * confirmation given against an older screen is cleared; a refused send re-reads the latest.
 */

const getTalkTalkStatus = vi.fn();
const listTalkTalkConversations = vi.fn();
const getTalkTalkConversation = vi.fn();
const sendTalkTalkReply = vi.fn();
const getTalkTalkSummary = vi.fn();

vi.mock("../../lib/apiClient", () => ({
  api: {
    getTalkTalkStatus: (...a: unknown[]) => getTalkTalkStatus(...a),
    listTalkTalkConversations: (...a: unknown[]) => listTalkTalkConversations(...a),
    getTalkTalkConversation: (...a: unknown[]) => getTalkTalkConversation(...a),
    sendTalkTalkReply: (...a: unknown[]) => sendTalkTalkReply(...a),
    getTalkTalkSummary: (...a: unknown[]) => getTalkTalkSummary(...a),
    prepareTalkTalkDraft: vi.fn(),
    editTalkTalkDraft: vi.fn(),
    handOverTalkTalk: vi.fn(),
  },
}));

function narrow() {
  const original = window.matchMedia;
  window.matchMedia = ((query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
    addListener: () => undefined,
    removeListener: () => undefined,
    dispatchEvent: () => false,
  })) as unknown as typeof window.matchMedia;
  return () => {
    window.matchMedia = original;
  };
}

const status: TalkTalkStatus = {
  connection: {
    connected: true,
    status: "CONNECTED",
    mode: "FAKE",
    fake: true,
    modeLabel: "모의 전송 — 실제 톡톡으로 가지 않아요",
    partner: "wc1***",
    lastEventAt: null,
    connectedAt: null,
  },
  readiness: { ready: false, items: [], residualRisk: "" },
  currentGuidance: null,
  draftGuidance: null,
  delegation: { active: false, reason: null, guidanceVersion: null, changedAt: null, answeredAutomatically: [], handedToPerson: [] },
  laneNotice: "합성 시험까지만 확인했어요.",
};

function detail(over: Partial<ConversationDetail> = {}, draftText = "배송 기준 안내드려요.", version = 1): ConversationDetail {
  return {
    conversation: {
      id: "c1",
      alias: "고객 A1B2",
      owner: "BOT",
      ownerLabel: "챗봇(리뷰너리)이 맡음",
      needsHuman: true,
      humanReason: { code: "DELEGATION_OFF", label: "자동 안내가 꺼져 있어요" },
      automation: null,
      lastInboundAt: null,
      lastPreview: "금요일에 쉬시나요?",
      lastOutboundStatus: null,
    },
    productName: null,
    leftAt: null,
    mode: "FAKE",
    fake: true,
    messages: [
      { id: "m1", author: "CUSTOMER", authorLabel: "고객", text: "금요일에 쉬시나요?", at: "2026-11-23T01:00:00Z", duplicateSuspect: false, standby: false, purged: false, outboundStatus: null, outboundStatusLabel: null, nonTextNote: null },
    ],
    draft: {
      version,
      authorKind: "GUIDANCE",
      text: draftText,
      fingerprint: `fp-${version}`,
      basis: "GROUNDED",
      holds: [],
      evidence: [{ kind: "GUIDANCE", title: "톡톡 배송 기준 v1" }],
      createdAt: "2026-11-23T01:00:00Z",
      forLatest: true,
    },
    outbound: [],
    ...over,
  };
}

let restore: () => void;
beforeEach(() => {
  vi.useFakeTimers({ shouldAdvanceTime: true });
  restore = narrow();
  getTalkTalkStatus.mockResolvedValue(status);
  listTalkTalkConversations.mockResolvedValue([]);
  getTalkTalkSummary.mockReset();
  getTalkTalkConversation.mockReset();
  sendTalkTalkReply.mockReset();
});
afterEach(() => {
  restore();
  vi.useRealTimers();
});

function openConversation() {
  return render(
    <MemoryRouter initialEntries={["/talktalk/c1"]}>
      <Routes>
        <Route path="/talktalk/:conversationId" element={<TalkTalkWorkspace />} />
        <Route path="/talktalk" element={<TalkTalkWorkspace />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe("TalkTalk conversation screen", () => {
  it("shows a queued send's result without a reload, and never says delivered", async () => {
    getTalkTalkConversation
      .mockResolvedValueOnce(detail({ outbound: [out("QUEUED", "보내기 대기")] }))
      .mockResolvedValue(detail({ outbound: [out("FAKE_RECORDED", "모의 기록 · 실제로 가지 않았어요")] }));
    openConversation();
    expect(await screen.findByText("보내기 대기")).toBeTruthy();
    // let the effect that re-arms the poll at the in-flight pace run before time moves
    await act(async () => {
      await new Promise((r) => setTimeout(r, 0));
    });
    await act(async () => {
      vi.advanceTimersByTime(2100); // fast while QUEUED: 2s, not the idle 6s
    });
    await waitFor(() => expect(getTalkTalkConversation.mock.calls.length).toBeGreaterThanOrEqual(2));
    expect(await screen.findByText("모의 기록 · 실제로 가지 않았어요")).toBeTruthy();
    expect(screen.queryByText(/전달됨|읽음/)).toBeNull();
    expect(screen.getByText(/고객이 받았거나 읽었다는 뜻은 아니에요/)).toBeTruthy();
  });

  it("re-reads the list as soon as the open conversation's result moves, not at the list's own 10s poll", async () => {
    const waiting = detail({ outbound: [out("QUEUED", "보내기 대기")] });
    const done = detail({ outbound: [out("FAKE_RECORDED", "모의 기록 · 실제로 가지 않았어요")] });
    done.conversation = { ...done.conversation, needsHuman: false, humanReason: null };
    getTalkTalkConversation.mockResolvedValueOnce(waiting).mockResolvedValue(done);
    openConversation();
    expect(await screen.findByText("보내기 대기")).toBeTruthy();
    const listReads = listTalkTalkConversations.mock.calls.length;
    await act(async () => {
      await new Promise((r) => setTimeout(r, 0));
    });
    await act(async () => {
      vi.advanceTimersByTime(2100);
    });
    expect(await screen.findByText("모의 기록 · 실제로 가지 않았어요")).toBeTruthy();
    await waitFor(() => expect(listTalkTalkConversations.mock.calls.length).toBe(listReads + 1));
  });

  it("keeps an unsaved sentence when a re-read brings a new draft, and says so", async () => {
    getTalkTalkConversation
      .mockResolvedValueOnce(detail())
      .mockResolvedValue(detail({}, "다른 사람이 바꾼 답변", 2));
    openConversation();
    const box = (await screen.findByTestId("tt-text")) as HTMLTextAreaElement;
    fireEvent.change(box, { target: { value: "제가 고치던 문장" } });
    await act(async () => {
      vi.advanceTimersByTime(6100);
    });
    await waitFor(() => expect(screen.getByTestId("tt-notice").textContent).toContain("그사이 답변이 바뀌었어요"));
    expect((screen.getByTestId("tt-text") as HTMLTextAreaElement).value).toBe("제가 고치던 문장");
  });

  it("clears a pending confirmation when the screen it was given against changes", async () => {
    const unknown = { ...out("UNKNOWN", "결과를 몰라요 · 톡톡에서 확인해 주세요"), kind: "MANUAL_REPLY" as const };
    getTalkTalkConversation.mockResolvedValueOnce(detail({ outbound: [unknown] })).mockResolvedValue(
      detail({
        outbound: [unknown],
        messages: [
          ...detail().messages,
          { id: "m2", author: "CUSTOMER", authorLabel: "고객", text: "그리고 환불은요?", at: "2026-11-23T01:02:00Z", duplicateSuspect: false, standby: false, purged: false, outboundStatus: null, outboundStatusLabel: null, nonTextNote: null },
        ],
      }),
    );
    openConversation();
    const ack = (await screen.findByRole("checkbox", { name: /실제로 가지 않은 것을 확인했어요/ })) as HTMLInputElement;
    fireEvent.click(ack);
    expect(ack.checked).toBe(true);
    await act(async () => {
      vi.advanceTimersByTime(6100);
    });
    await waitFor(() =>
      expect((screen.getByRole("checkbox", { name: /실제로 가지 않은 것을 확인했어요/ }) as HTMLInputElement).checked).toBe(false),
    );
  });

  it("re-reads the latest after the server says a newer message arrived", async () => {
    getTalkTalkConversation.mockResolvedValue(detail());
    sendTalkTalkReply.mockRejectedValue({
      response: { data: { code: "NEWER_MESSAGE", message: "답변을 준비한 뒤 고객이 새 메시지를 보냈어요." } },
    });
    openConversation();
    fireEvent.click(await screen.findByTestId("tt-send-first"));
    const calls = getTalkTalkConversation.mock.calls.length;
    fireEvent.click(screen.getByTestId("tt-send-confirm"));
    await waitFor(() => expect(screen.getByTestId("tt-notice").textContent).toContain("최신 내용을 다시 불러왔어요"));
    expect(getTalkTalkConversation.mock.calls.length).toBeGreaterThan(calls);
    expect(screen.getByTestId("tt-error").textContent).toContain("새 메시지");
  });

  it("the two-step send names the reviewed version and fingerprint", async () => {
    getTalkTalkConversation.mockResolvedValue(detail());
    sendTalkTalkReply.mockResolvedValue(detail({ outbound: [out("QUEUED", "보내기 대기")] }));
    openConversation();
    fireEvent.click(await screen.findByTestId("tt-send-first"));
    fireEvent.click(screen.getByTestId("tt-send-confirm"));
    await waitFor(() => expect(sendTalkTalkReply).toHaveBeenCalled());
    const [, body] = sendTalkTalkReply.mock.calls[0];
    expect(body).toMatchObject({ draftVersion: 1, expectedFingerprint: "fp-1", confirmHolds: false, acknowledgeUnknownOf: null });
    expect(typeof body.commandId).toBe("string");
  });
});

describe("non-text messages", () => {
  it("a photo is never shown as an empty line: it says what it was and where to see it", async () => {
    const d = detail();
    d.messages = [
      { ...d.messages[0], text: null, nonTextNote: "사진을 보냈어요. 이 서비스에는 사진이 저장되지 않아요 — 톡톡 파트너센터에서 확인해 주세요." },
    ];
    getTalkTalkConversation.mockResolvedValue(d);
    openConversation();
    const thread = await screen.findByTestId("tt-thread");
    expect(thread.textContent).toContain("파트너센터에서 확인해 주세요");
    expect(Array.from(thread.querySelectorAll("p")).some((p) => p.textContent?.trim() === "—")).toBe(false);
  });
});

describe("late answers (UI review: epoch)", () => {
  it("a poll that answers after the send never overwrites the send's result", async () => {
    let releasePoll: (v: ConversationDetail) => void = () => undefined;
    getTalkTalkConversation
      .mockResolvedValueOnce(detail())
      .mockImplementationOnce(() => new Promise<ConversationDetail>((r) => (releasePoll = r)))
      .mockResolvedValue(detail({ outbound: [out("FAKE_RECORDED", "모의 기록 · 실제로 가지 않았어요")] }));
    let releaseSend: (v: ConversationDetail) => void = () => undefined;
    sendTalkTalkReply.mockImplementation(() => new Promise<ConversationDetail>((r) => (releaseSend = r)));
    openConversation();
    await screen.findByTestId("tt-send-first");
    await act(async () => {
      await new Promise((r) => setTimeout(r, 0));
    });
    await act(async () => {
      vi.advanceTimersByTime(6100); // a poll starts and hangs
    });
    fireEvent.click(screen.getByTestId("tt-send-first"));
    fireEvent.click(screen.getByTestId("tt-send-confirm"));
    // while the send is out, the sentence cannot be typed into (it would be lost when the result lands)
    expect((screen.getByTestId("tt-text") as HTMLTextAreaElement).readOnly).toBe(true);
    await act(async () => {
      releaseSend(detail({ outbound: [out("QUEUED", "보내기 대기")] }));
    });
    expect(await screen.findByText("보내기 대기")).toBeTruthy();
    await act(async () => {
      releasePoll(detail()); // the old poll answers last: an older screen
    });
    expect(screen.getByText("보내기 대기")).toBeTruthy();
    expect((screen.getByTestId("tt-text") as HTMLTextAreaElement).readOnly).toBe(false);
  });

  it("an answer for another conversation is never shown in this one", async () => {
    const other = detail();
    other.conversation = { ...other.conversation, id: "c2", alias: "고객 ZZZZ" };
    getTalkTalkConversation.mockResolvedValueOnce(detail()).mockResolvedValue(other);
    openConversation();
    await screen.findByText("고객 A1B2");
    await act(async () => {
      await new Promise((r) => setTimeout(r, 0));
    });
    await act(async () => {
      vi.advanceTimersByTime(6100);
    });
    await waitFor(() => expect(getTalkTalkConversation.mock.calls.length).toBeGreaterThanOrEqual(2));
    expect(screen.queryByText("고객 ZZZZ")).toBeNull();
    expect(screen.getByText("고객 A1B2")).toBeTruthy();
  });
});

describe("send tiers (mirror the server; it checks again)", () => {
  it("a held model draft must be rewritten; a seller's unbacked promise is a confirmable exception; a conflict is not", () => {
    const held = detail();
    held.draft = { ...held.draft!, authorKind: "MODEL", holds: [{ code: "CLAIM_SELLER_PROMISE", label: "약속" }] };
    expect(sendAbility(held).kind).toBe("rewrite");
    held.draft = { ...held.draft!, authorKind: "SELLER_EDIT" };
    expect(sendAbility(held).kind).toBe("confirm-exception");
    held.draft = { ...held.draft!, holds: [{ code: "COST_CONFLICT", label: "반대" }] };
    expect(sendAbility(held).kind).toBe("rewrite");
    held.draft = { ...held.draft!, holds: [{ code: "FORBIDDEN_PHRASE", label: "금지" }] };
    expect(sendAbility(held).kind).toBe("rewrite");
    const stale = detail();
    stale.draft = { ...stale.draft!, forLatest: false };
    expect(sendAbility(stale).kind).toBe("none");
  });

  it("the decision key moves with the draft, the unknown send and the newest line", () => {
    const a = detail();
    expect(decisionKey(a)).not.toBe(decisionKey(detail({}, "x", 2)));
    expect(decisionKey(a)).not.toBe(decisionKey(detail({ outbound: [{ ...out("UNKNOWN", ""), kind: "MANUAL_REPLY" }] })));
  });
});

describe("TalkTalk on the home", () => {
  it("counts waiting conversations and says the home numbers exclude TalkTalk", async () => {
    getTalkTalkSummary.mockResolvedValue({
      connected: true,
      fake: true,
      needsHumanConversations: 2,
      unknownSends: 1,
      queuedSends: 0,
      notice: "홈의 문의·리뷰 숫자에는 톡톡 상담이 들어가지 않아요. 톡톡은 대화 단위로 따로 세요.",
    });
    render(
      <MemoryRouter>
        <TalkTalkHomeLine />
      </MemoryRouter>,
    );
    const line = await screen.findByTestId("cs-talktalk");
    expect(line.textContent).toContain("사람 확인 대기 대화 2개");
    expect(line.textContent).toContain("결과를 모르는 발신 1건");
    expect(line.textContent).toContain("들어가지 않아요");
  });

  it("stays out of the way when TalkTalk is not connected and nothing waits", async () => {
    getTalkTalkSummary.mockResolvedValue({ connected: false, fake: true, needsHumanConversations: 0, unknownSends: 0, queuedSends: 0, notice: "" });
    render(
      <MemoryRouter>
        <TalkTalkHomeLine />
      </MemoryRouter>,
    );
    await waitFor(() => expect(getTalkTalkSummary).toHaveBeenCalled());
    expect(screen.queryByTestId("cs-talktalk")).toBeNull();
  });
});

function out(status: ConversationDetail["outbound"][number]["status"], label: string): ConversationDetail["outbound"][number] {
  return {
    id: `o-${status}`,
    kind: "MANUAL_REPLY",
    kindLabel: "직접 보낸 답변",
    status,
    statusLabel: label,
    failure: null,
    resultCode: null,
    mode: "FAKE",
    echoSeen: false,
    createdAt: "2026-11-23T01:01:00Z",
    finishedAt: null,
    byService: false,
  };
}
