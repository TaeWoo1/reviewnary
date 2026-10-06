// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { existsSync, readFileSync } from "node:fs";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { AgentPanelProvider } from "../../lib/agentPanel";
import { ConversationProvider } from "../../lib/conversation/ConversationProvider";
import { AgentHome, greetingLine, contextStrip } from "./AgentHome";
import { agentTurn } from "../../test/conversationFixtures";
import type { MetricKpi, OverviewResponse } from "../../lib/types";
import { COPY } from "../../lib/copy/customerOps";

const getOverviewStrict = vi.fn();
const getProactiveCases = vi.fn();
const getInquiryQueueStrict = vi.fn();
const getInquiryRowsStrict = vi.fn();
const getReviewIssuesStrict = vi.fn();
const getOperationsHomeStrict = vi.fn();
const recordHomeOpened = vi.fn();
const getCustomerOperationsHome = vi.fn();
vi.mock("../../../lib/bridge/localAgentHint", () => ({ probeLocalAgent: async () => "PAIRED" }));
vi.mock("../../lib/apiClient", () => ({
  api: {
    getOverviewStrict: (d?: number) => getOverviewStrict(d),
    getProactiveCases: (n?: number) => getProactiveCases(n),
    getInquiryQueueStrict: (p: unknown) => getInquiryQueueStrict(p),
    getInquiryRowsStrict: (p: unknown) => getInquiryRowsStrict(p),
    getReviewIssuesStrict: () => getReviewIssuesStrict(),
    getOperationsHomeStrict: () => getOperationsHomeStrict(),
    getSyncRunsStrict: vi.fn(async () => []),
    markProactiveCaseOpened: vi.fn(),
    // The return-visit signal: fire-and-forget, awaited by nothing, rendered by nothing.
    recordHomeOpened: () => recordHomeOpened(),
    getCustomerOperationsHome: () => getCustomerOperationsHome(),
  },
  getToken: () => null,
}));
vi.mock("../../lib/conversation/conversationClient", () => ({
  conversationClient: {
    createConversation: vi.fn(async () => ({ conversationId: "c-1", createdAt: "x" })),
    getConversation: vi.fn(),
    listConversations: vi.fn(async () => [{ conversationId: "c-old", createdAt: "x", updatedAt: "2026-08-27T08:00:00Z", turnCount: 4, headline: "지난 리뷰 확인" }]),
    sendTurn: vi.fn(),
  },
}));
import { conversationClient } from "../../lib/conversation/conversationClient";

function kpi(key: string, label: string, value: number, comparable = true, freshnessUnproven = false): MetricKpi {
  return { key, label, value, unit: "건", previousValue: comparable ? 0 : null, deltaPercent: null, comparable, excludedChannels: 0, freshnessUnproven };
}

function overview(over: Partial<OverviewResponse["metrics"]> = {}): OverviewResponse {
  return {
    metrics: {
      period: { from: "2026-08-21", to: "2026-08-27", previousFrom: "2026-08-14", previousTo: "2026-08-20", days: 7 },
      revenueBasis: "결제 완료 기준",
      orderCountBasis: "주문 건수 기준",
      kpis: [kpi("orders", "주문", 12), kpi("unansweredInquiries", "미답변 문의", 22, false), kpi("negativeReviews", "부정 리뷰", 3)],
      series: [{ key: "orders", label: "주문", unit: "건", points: [{ date: "2026-08-26", value: 5 }, { date: "2026-08-27", value: 4 }] }],
      channels: [{ channelCode: "CAFE24", channelNameKo: "카페24", orderState: "OBSERVED_FRESH", revenue: 1, orders: 1, countedInOrders: true, inquiryState: "OBSERVED_FRESH", inquiries: 1, unansweredInquiries: 1, countedInInquiries: true, countedInUnansweredNow: true, reviewState: "OBSERVED_FRESH", reviews: 1, negativeReviews: 0, countedInReviews: true, connected: true, connectable: true }],
      exclusions: [],
      exampleDataIncluded: false,
      ...over,
    },
    insights: [],
  };
}

const CASE = {
  id: "case-1", subjectKind: "INQUIRY", subjectId: "i-1", workItemId: "w-1", channelId: "ch", channelNameKo: "카페24 자사몰", productId: null, productName: null,
  snippet: "배송은 언제 되나요?", rating: null, priority: "HIGH", reason: "UNANSWERED", reasonNote: "답변 초안을 준비했습니다", evidenceState: null, evidenceCount: 1,
  knowledgeGap: null, preparedAction: "DRAFT_PREPARED", draftVersion: 1, recommendation: null, subjectReceivedAt: null, preparedAt: null,
};

const MORNING = new Date("2026-08-27T09:30:00+09:00");

function renderHome(now = MORNING) {
  return render(
    <MemoryRouter>
      <AgentPanelProvider>
        <ConversationProvider>
          <AgentHome now={now} />
        </ConversationProvider>
      </AgentPanelProvider>
    </MemoryRouter>,
  );
}

/**
 * Operations Home's four areas, shaped like the live Demo Org: 15 reviews in 확인 필요 of which 13 are
 * undecided, 122 in 지켜보기, 20 repeated problems of which one is anybody's move.
 */
const HOME = {
  reviews: {
    needsAttentionUndecided: 13,
    needsAttentionTotal: 15,
    watchTotal: 122,
    rows: [
      {
        reviewId: "rev-1",
        accountId: "acc-1",
        channelCode: "NAVER",
        rating: 1,
        occurredOn: "2026-07-23",
        productName: "선바로 전선몰딩",
        quote: "상품페이지 설명에 혼선을 줍니다.",
      },
    ],
  },
  problems: {
    decidable: 1,
    observing: 19,
    rows: [
      {
        issue: {
          id: "iss-1",
          title: "접착 부족",
          aspect: "접착",
          problem: "부족",
          severity: "NORMAL",
          lifecycleState: "ACTING",
          lifecycleLabelKo: "조치 중",
          evidenceCount: 18,
          firstEvidenceOn: "2025-07-29",
          lastEvidenceOn: "2026-08-19",
          dominantProductId: "prod-1",
          dominantProductName: "선바로 전선몰딩",
          dismissed: false,
          extractorKind: "RULE_BASED",
          change: { kinds: [], labelsKo: [], highSurge: false, surgeWindowCount: 0, surgeBaselineWeekly: 0 },
        },
        context: {
          issueId: "iss-1",
          aspect: "접착",
          evidence: {
            totalEvidence: 18,
            byProduct: [{
              productId: "prod-1", productName: "선바로 전선몰딩", evidenceCount: 16,
              productReviews: 1761, firstOccurredOn: "2025-07-29", lastOccurredOn: "2026-08-19",
            }],
            unattributedEvidence: 0,
            ratingDistribution: { rating1: 0, rating2: 0, rating3: 3, rating4: 5, rating5: 10, unrated: 0 },
            firstEvidenceOn: "2025-07-29",
            lastEvidenceOn: "2026-08-19",
          },
          knowledge: {
            productId: "prod-1", productName: "선바로 전선몰딩", productSources: 3, productMentions: 2,
            orgSources: 2, orgMentions: 0, excerpts: [],
          },
        },
      },
    ],
  },
  collection: [{
    channelCode: "NAVER", channelNameKo: "네이버 스마트스토어", dataType: "REVIEW",
    state: "OBSERVED_FRESH", supported: true, verificationStatus: null, connected: true,
    connectionStatus: "CONNECTED", routineEnabled: true, routinePausedBy: null,
    lastSuccessfulSyncAt: "2026-09-08T03:06:57Z", rows: 100, openRows: 3,
    newestObservedAt: "2026-09-08T03:00:00Z",
  }],
  prepared: {
    reviewRepliesApproved: 4,
    inquiryDraftsReady: 2,
    rows: [{ kind: "REVIEW_REPLY", id: "rev-9", label: "승인된 리뷰 답변", detail: "선바로 전선몰딩", channelCode: "NAVER", to: "/reviews/reply/rev-9" }],
  },
} as never;

beforeEach(() => {
  window.localStorage.clear();
  getOverviewStrict.mockResolvedValue(overview());
  getProactiveCases.mockResolvedValue({ items: [CASE, { ...CASE, id: "case-2", subjectId: "i-2", snippet: "교환 가능한가요?" }], total: 2, high: 2 });
  // §1: the home brief reads the WORK QUEUE. Empty by default — a test that is about waiting work says so.
  getInquiryQueueStrict.mockResolvedValue({ content: [], page: 0, size: 5, totalElements: 0, totalPages: 0 });
  getOperationsHomeStrict.mockResolvedValue(HOME);
  recordHomeOpened.mockResolvedValue(undefined);
  // 고객 운영 관리 not opened for the org by default: the Home stays the conversation-first Home these suites describe.
  getCustomerOperationsHome.mockResolvedValue({ available: false });
  getReviewIssuesStrict.mockResolvedValue([]);
  // Working Context v1 §2: the brief names the oldest waiting inquiries. Empty by default —
  // the tests that care about the named rows set their own.
  getInquiryRowsStrict.mockResolvedValue({ from: null, to: null, channel: null, status: "UNANSWERED", order: "OLDEST", limit: 3, term: null, totalCount: 0, items: [] });
  vi.mocked(conversationClient.sendTurn).mockReset();
});
afterEach(() => vi.clearAllMocks());

describe("greeting — arithmetic, never a model", () => {
  it("is the hour plus the count of what was prepared; zero gets its own sentence", () => {
    expect(greetingLine(9, 2)).toBe("좋은 아침입니다. 오늘 제가 먼저 확인한 일이 2개 있습니다.");
    // §11: zero says only hello — whether anything is WAITING is the opener turn's sentence, computed
    // from the real workload, so the greeting can never contradict it.
    expect(greetingLine(15, 0)).toBe("안녕하세요.");
    expect(greetingLine(15, null)).toBe("안녕하세요.");
  });

  /**
   * <b>Obligations only</b> (product-owner decision, 2026-09-30). This test used to pin three entries —
   * 현재 미답변 문의 · 오늘 주문 · 최근 7일 부정 리뷰 — and the window rule that chose between 「오늘 주문」 and
   * 「최근 7일 주문」. 주문량 and a record count are not things waiting for the seller, and 주문 · 매출 · 추이
   * are owned by `/overview`, which the line still links to. Both numbers are one press away and unchanged;
   * what the Home stopped doing is quoting the dashboard on the screen whose subject is the work.
   */
  it("the context strip carries the waiting work and nothing else", () => {
    const strip = contextStrip(overview());
    expect(strip.map((k) => k.label)).toEqual(["현재 미답변"]);
    // The same noun the other Home's band uses for the same fact — one product, one word.
    expect(strip.map((k) => k.label)).not.toContain("현재 미답변 문의");
    expect(contextStrip(overview(), false, 22).map((k) => k.label)).toEqual(["확인할 일"]);
    expect(contextStrip(overview(), false, 22)[0]!.value).toBe(22);
    // 주문 and 부정 리뷰 are gone from the Home, in both readings.
    for (const labels of [contextStrip(overview()).map((k) => k.label), contextStrip(overview(), false, 22).map((k) => k.label)]) {
      expect(labels.join(" ")).not.toMatch(/주문|부정 리뷰/);
    }
    // The brief names the waiting inquiries: the line then has nothing left to say and says nothing.
    expect(contextStrip(overview(), true)).toEqual([]);
  });
});

/**
 * <b>연결된 org의 Home은 하나다</b> (product-owner decision, 2026-10-06).
 *
 * <p>이 블록은 두 개의 Home을 검사하던 두 블록(「the Agent operating workspace」의 브리핑 턴들과
 * 「Operations Home — 지금 확인할 것」의 네 영역)을 대신한다. 어느 쪽이 보이는지를 정하던 것은 판매자의
 * 데이터가 아니라 배포 설정과 한 번의 읽기 성공 여부였고, 그래서 같은 판매자가 아침마다 다른 제품을 열 수
 * 있었다. 이제 골격은 하나이고, 자동 확인에 <b>속한</b> 줄만 그 일이 열려 있을 때 선다.
 */
describe("home — 연결된 org이 보는 하나의 Home", () => {
  it("골격이 선다 — 오늘 · 숫자 · 오늘 먼저 볼 일 · 반복 문제 · 채널, 그리고 그 아래 한 줄 입력", async () => {
    renderHome();
    const title = await screen.findByRole("heading", { level: 1, name: "오늘" });
    expect(title.className).toContain("text-title");
    expect(screen.getByText("오늘 먼저 볼 일")).toBeInTheDocument();
    expect(screen.getByLabelText("오늘 달라진 점")).toBeInTheDocument();
    expect(screen.getByLabelText("반복 문제")).toBeInTheDocument();
    expect(screen.getByLabelText("채널 상태")).toBeInTheDocument();
    expect(screen.getByRole("form", { name: "AI 담당자에게 요청" })).toBeInTheDocument();
    // 두 번째 Home은 없다 — 같은 아침이 두 가지 모양을 갖지 않는다.
    expect(screen.queryByLabelText("오늘 확인할 것")).toBeNull();
    expect(screen.queryByLabelText("지금 확인할 리뷰")).toBeNull();
    expect(screen.queryByLabelText("최근 수집 상태")).toBeNull();
    // 우리 어휘는 화면에 없다.
    expect(screen.queryByText(/proactive|PROPOSED|DRAFT_PREPARED|case/i)).toBeNull();
  });

  it("자동 확인에 속한 것은 그 일이 없으면 말하지 않는다 — 골격은 그대로", async () => {
    renderHome();
    await screen.findByRole("heading", { level: 1, name: "오늘" });
    // 상태 점 · 마지막 확인 · 「시작」은 책임 런타임의 것이다. 열려 있지 않은 배포에서 그리면 켜 본 적도
    // 없는 기능의 건강을 보고하고, 누를 수 없는 버튼을 첫 화면에 두는 일이 된다.
    expect(screen.queryByText(/마지막 확인/)).toBeNull();
    expect(screen.queryByRole("button", { name: COPY.start })).toBeNull();
    expect(screen.getByTestId("today-status")).toBeInTheDocument();
  });

  it("브리핑 턴과 예시 칩은 없다 — 할 일 목록이 이미 그것을 말한다", async () => {
    renderHome();
    await screen.findByRole("heading", { level: 1, name: "오늘" });
    expect(screen.queryAllByTestId("agent-turn")).toHaveLength(0);
    expect(screen.queryByLabelText("예시 질문")).toBeNull();
    expect(screen.queryByText(/지금 처리할 일이/)).toBeNull();
    expect(screen.queryByLabelText("오늘 상태")).toBeNull();
  });

  it("먼저 볼 일의 리뷰 한 줄은 그 리뷰를 판단하는 화면을 연다", async () => {
    renderHome();
    await screen.findByText("오늘 먼저 볼 일");
    const row = await screen.findByRole("link", { name: /상품페이지 설명에 혼선을 줍니다/ });
    // `from=work` — 이 화면에서 들어왔다는 표시. 돌아올 곳을 리뷰 화면이 안다.
    expect(row).toHaveAttribute("href", "/reviews/reply/rev-1?from=work");
  });

  it("반복 문제는 분모와 함께, 비율 없이 — 그리고 그 근거 화면으로", async () => {
    renderHome();
    const area = await screen.findByLabelText("반복 문제");
    expect(within(area).getByRole("link", { name: /접착 부족/ })).toHaveAttribute("href", "/memory/iss-1");
    expect(area.textContent ?? "").not.toMatch(/%|퍼센트|비율/);
  });

  it("실행 대기는 승인해 둔 것을 한 건씩 이름으로 부른다", async () => {
    renderHome();
    const area = await screen.findByLabelText("실행 대기");
    const link = within(area).getByRole("link", { name: /선바로 전선몰딩/ });
    expect(link).toHaveAttribute("href", "/reviews/reply/rev-9");
    expect(area.textContent ?? "").not.toContain("6건");
  });

  it("채널 상태는 수집을 기술 이름 없이 말한다", async () => {
    renderHome();
    const area = await screen.findByLabelText("채널 상태");
    expect(within(area).getByRole("link", { name: "연결 상태 보기" })).toHaveAttribute("href", "/connect");
    expect(area.textContent ?? "").not.toMatch(/NAVER|ORDER_SUMMARY|REVIEW|GW\./);
  });

  /**
   * <b>읽기가 실패해도 골격은 무너지지 않는다.</b> 전에는 이 상황에서 네 영역이 통째로 사라지고 화면이
   * 대화 한 줄로 줄었다 — 같은 아침이 읽기 실패 하나로 다른 제품이 됐다. 이제 화면은 그대로 서고, 실패한
   * 읽기가 소유한 줄만 비며, 어느 칸도 0을 지어내지 않는다.
   */
  it("보유 읽기가 실패해도 화면은 그대로 서고, 0을 지어내지 않는다", async () => {
    getOperationsHomeStrict.mockRejectedValue(new Error("down"));
    renderHome();
    await screen.findByRole("heading", { level: 1, name: "오늘" });
    expect(screen.getByLabelText("채널 상태")).toBeInTheDocument();
    expect(screen.queryByLabelText("실행 대기")).toBeNull();
    expect(screen.queryByText(/반복 문제 0건/)).toBeNull();
  });

  it("아무것도 기다리지 않는 아침은 그렇게 적는다", async () => {
    getOperationsHomeStrict.mockResolvedValue({
      reviews: { needsAttentionUndecided: 0, needsAttentionTotal: 0, watchTotal: 0, rows: [] },
      problems: { decidable: 0, observing: 0, rows: [] },
      collection: [],
      prepared: { reviewRepliesApproved: 0, inquiryDraftsReady: 0, rows: [] },
    });
    renderHome();
    expect(await screen.findByText("지금 확인할 일이 없습니다.")).toBeInTheDocument();
  });

  /**
   * <b>돌아오지 않는다</b> (reviewnary_design §8-A v3.3). 두 번째 Home이 그려지던 조건은 둘이었고 —
   * 배포가 책임 런타임을 열지 않았거나(`available: false`), 그 한 번의 읽기가 실패했거나 — 둘 다 판매자의
   * 데이터가 아니다. 두 경우 모두 같은 골격이 서는지 여기서 검사한다.
   */
  it("고객 운영 관리 읽기가 실패해도 예전 Home으로 돌아가지 않는다", async () => {
    getCustomerOperationsHome.mockRejectedValue(new Error("responsibility down"));
    renderHome();
    await screen.findByRole("heading", { level: 1, name: "오늘" });
    expect(screen.getByText("오늘 먼저 볼 일")).toBeInTheDocument();
    expect(screen.getByLabelText("채널 상태")).toBeInTheDocument();
    // 네 개의 성긴 영역도, 그 아래 「지금 처리할 일이 N건」 대화 턴도 없다.
    expect(screen.queryByLabelText("오늘 확인할 것")).toBeNull();
    expect(screen.queryByLabelText("지금 확인할 리뷰")).toBeNull();
    expect(screen.queryByLabelText("최근 수집 상태")).toBeNull();
    expect(screen.queryByText(/지금 처리할 일이/)).toBeNull();
    expect(screen.queryAllByTestId("agent-turn")).toHaveLength(0);
  });

  it("legacy Home 모듈은 저장소에 없고, 어디서도 import되지 않는다", () => {
    // 화면 검사만으로는 「그 파일이 아직 있고 다른 조건에서 그려진다」를 잡지 못한다. 모듈 자체의 부재가
    // 계약이다 — 되살리려면 이 테스트를 먼저 지워야 한다.
    expect(existsSync("src/components/home/OperationsAreas.tsx")).toBe(false);
    const home = readFileSync("src/pages/app/AgentHome.tsx", "utf-8");
    expect(home).not.toContain("OperationsAreas");
    // 대화 브리핑 기계도 함께 사라졌다 — 남아 있으면 「연결된 org의 Home이 대화로 열리는 길」이 남은 것이다.
    for (const dead of ["proactiveTurn", "workloadPriorities", "storedInquiries", "leadingTurns"]) {
      expect(home, `${dead} is dead with the conversational Home`).not.toContain(dead);
    }
  });

  it("before the first connection the greeting stops counting and offers the one thing to do", async () => {
    getOverviewStrict.mockResolvedValue(overview({ channels: [] }));
    renderHome();
    expect(await screen.findByText("판매 채널을 연결하면 시작할 수 있습니다.")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "채널 연결하기" })).toHaveAttribute("href", "/connect");
    expect(screen.queryByLabelText("오늘 상태")).toBeNull();
    // 첫 연결 전에는 목록이 아니라 「무엇을 연결하면 무엇을 받는가」가 화면이고, 예시 칩이 거기 선다.
    expect(screen.getByLabelText("예시 질문")).toBeInTheDocument();
  });

  it("an exact shortcut answers locally with an object; no turn is sent", async () => {
    // 「답변이 필요한 문의」 is ONE question, so the heading, the count, the rows and the 전체 보기 all come
    // from the read that answers it — the record under `status=UNANSWERED`.
    getInquiryRowsStrict.mockResolvedValue({
      totalCount: 22, limit: 5, productId: null,
      items: [{ workItemId: "w1", inquiryId: "i1", sellerAccountId: "s", channelId: "c", channelCode: "CAFE24", channelNameKo: "카페24 자사몰", productId: null, productName: null, phase: "OPEN", status: "UNANSWERED", title: "배송 언제 되나요?", receivedAt: "2026-08-26T00:00:00Z" }],
    });
    renderHome();
    await screen.findByRole("heading", { level: 1, name: "오늘" });
    await userEvent.type(screen.getByPlaceholderText(COPY.composer), "미답변 문의 보여줘");
    await userEvent.keyboard("{Enter}");
    await screen.findByText("답변이 필요한 문의 22건");
    const turns = screen.getAllByTestId("agent-turn");
    const answer = turns[turns.length - 1]!;
    expect(within(answer).getByRole("button", { name: /배송 언제 되나요/ })).toBeInTheDocument();
    expect(conversationClient.sendTurn).not.toHaveBeenCalled();
  });

  it("every other sentence goes to the runtime", async () => {
    vi.mocked(conversationClient.sendTurn).mockResolvedValue(agentTurn());
    renderHome();
    await screen.findByRole("heading", { level: 1, name: "오늘" });
    await userEvent.type(screen.getByPlaceholderText(COPY.composer), "오늘 리뷰 뭐 들어왔어?");
    await userEvent.keyboard("{Enter}");
    await waitFor(() => expect(conversationClient.sendTurn).toHaveBeenCalledTimes(1));
    expect(vi.mocked(conversationClient.sendTurn).mock.calls[0]![1].text).toBe("오늘 리뷰 뭐 들어왔어?");
    expect(await screen.findByText("이 상품에 미답변 문의는 없습니다.")).toBeInTheDocument();
  });
});

/**
 * The pilot's return-visit signal (Pilot Launch Readiness §2). Both properties are about the seller,
 * not the number: they must not be able to tell it happened, and it must not be able to hurt them.
 */
describe("return-visit signal", () => {
  it("is sent once when 홈 opens, with nothing in it", async () => {
    renderHome();
    await screen.findByRole("heading", { name: "오늘" });
    await waitFor(() => expect(recordHomeOpened).toHaveBeenCalledTimes(1));
    // No argument: the organisation is the token's and the day is the server's, so the page has no
    // way to claim who opened it or when — which is also why it has nothing to leak.
    expect(recordHomeOpened).toHaveBeenCalledWith();
  });

  it("renders the whole morning when the signal fails", async () => {
    recordHomeOpened.mockRejectedValue(new Error("measurement down"));
    renderHome();
    await screen.findByRole("heading", { name: "오늘" });
    // The screen the seller came for is drawn exactly as it is when the signal succeeds.
    expect(await screen.findByText("오늘 먼저 볼 일")).toBeTruthy();
  });
});

describe("Customer Operations v3.1 — the job's Home", () => {
  const CO = {
    available: true, eligible: true, status: "ACTIVE", cadenceMinutes: 120,
    lastCheckedAt: "2026-08-27T00:02:00Z", lastRunStatus: "SUCCESS", nextCheckAt: "2026-08-27T02:00:00Z",
    sources: [],
    decisions: { total: 1, rows: [{
      caseId: "k-1", subjectKind: "INQUIRY", channelNameKo: "카페24", title: "배송은 언제 되나요?", rating: null,
      reasonNote: "답변 대기", summary: "출고 기준이 등록돼 있습니다.", recommendedActionType: "REPLY_TO_CUSTOMER",
      recommendedAction: null, missingInformation: [], draftPrepared: true, decidedBy: "AGENT",
      openedAt: "2026-08-27T00:02:00Z", to: "/inquiries/i-1",
    }] },
    handled: { since: null, autoResolved: 5, monitoring: 1, draftsPrepared: 2, verifying: 0, rows: [], checked: 9 },
    gaps: { total: 0, rows: [] },
  };

  it("replaces the numbers line, the opener turn and the four areas — one list, no second copy of the same inquiry", async () => {
    getCustomerOperationsHome.mockResolvedValue(CO);
    getInquiryQueueStrict.mockResolvedValue({
      content: [{ workItemId: "w-1", inquiryId: "i-1", sellerAccountId: "a", channelId: "c", channelCode: "CAFE24", channelNameKo: "카페24",
        productId: null, productName: null, phase: "PROPOSED", status: "UNANSWERED", title: "배송은 언제 되나요?", snippet: null,
        receivedAt: "2026-08-26T23:00:00Z", hasDraft: true }],
      page: 0, size: 50, totalElements: 1, totalPages: 1,
    });
    renderHome();

    const status = await screen.findByTestId("today-status");
    // The 24-hour tally moved to `/customer-operations` (product-owner decision, 2026-09-26); what this screen
    // still needs from the line is that the job is running, because that is what makes the list below readable.
    expect(status).toHaveTextContent("자동 확인 중");
    expect(status).not.toHaveTextContent("최근 24시간 자동 확인");
    // 홈 → 오늘 (UI/UX v2 Phase 1, product-owner decision).
    expect(screen.getByRole("heading", { level: 1, name: "오늘" })).toBeInTheDocument();
    // The case and the queue row are the same inquiry: drawn once, as the case.
    const list = await screen.findByRole("list", { name: "확인할 일" });
    const hrefs = within(list).getAllByRole("link").map((a) => a.getAttribute("href"));
    expect(hrefs).toContain("/customer-operations/cases/k-1");
    expect(hrefs).not.toContain("/inquiries/i-1");
    expect(within(list).getAllByText("배송은 언제 되나요?")).toHaveLength(1);
    // Nothing of the other Home is drawn beside it.
    expect(screen.queryByRole("region", { name: "오늘 상태" })).toBeNull();
    expect(screen.queryByText("AI가 먼저 확인한 일")).toBeNull();
    expect(screen.queryByText("지금 확인할 리뷰")).toBeNull();
    // The composer's own string, not a copy of it: the placeholder changed with the Home visual target
    // (「리뷰나 문의를 자연어로 요청해보세요」) and a second spelling of it here is exactly the kind of
    // literal that makes a copy change look like a regression.
    expect(screen.getByPlaceholderText(COPY.composer)).toBeInTheDocument();
  });
});

