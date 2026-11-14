// @vitest-environment jsdom
import { fireEvent, render, screen, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { afterEach, describe, expect, it, vi } from "vitest";
import { CsHome } from "./CsHome";
import { boardFrom } from "../../test/homeBoardFake";

const tally = (n: number) => ({ count: n, atLeast: n });
const metricsChannel = (code: string, name: string, over: Record<string, unknown> = {}) => ({
  channelCode: code, channelNameKo: name, orderState: "OBSERVED_FRESH", revenue: 0, orders: 0, countedInOrders: true,
  inquiryState: "OBSERVED_FRESH", inquiries: 2, unansweredInquiries: 1, countedInInquiries: true, countedInUnansweredNow: true,
  reviewState: "OBSERVED_FRESH", reviews: 3, negativeReviews: 0, countedInReviews: true, connected: true, connectable: true, ...over,
});

const board = boardFrom([], {
  asOf: "2026-11-13T02:52:00Z",
  todo: {
    total: tally(8), safety: tally(1), approvable: tally(3), praiseApprovable: tally(0), needsInfo: tally(1), needsReply: tally(1),
    needsLook: tally(0), checkAgain: tally(2), alsoDueToday: tally(1), todayChecks: tally(3),
    answer: { total: tally(6), reviews: tally(3), inquiries: tally(3) },
  },
  waiting: { total: tally(4), registration: tally(1), action: tally(2), customer: tally(1) },
});

// Per-test switches the mocked reads look at (hoisted with the mock).
const sw = vi.hoisted(() => ({ overviewFails: false, workSources: [] as Array<{ name: string; read: boolean; complete: boolean }>, workCount: 8 as number | null }));

vi.mock("../../lib/apiClient", () => ({
  api: {
    getOverviewStrict: async () => {
      if (sw.overviewFails) throw new Error("down");
      return {
      metrics: {
        period: { from: "2026-11-13", to: "2026-11-13", previousFrom: "2026-11-12", previousTo: "2026-11-12", days: 1 },
        kpis: [
          { key: "inquiries", label: "문의", value: 4, unit: "건", previousValue: 0, deltaPercent: null, comparable: true, excludedChannels: 0, freshnessUnproven: false },
          { key: "reviews", label: "리뷰", value: 6, unit: "건", previousValue: 0, deltaPercent: null, comparable: true, excludedChannels: 1, freshnessUnproven: false },
        ],
        series: [],
        channels: [
          metricsChannel("NAVER", "네이버 스마트스토어"),
          metricsChannel("COUPANG", "쿠팡", { reviewState: "NOT_SUPPORTED", reviews: 0, countedInReviews: false }),
        ],
        exclusions: [],
      },
    };
    },
    getHomeBoard: async () => board,
    getHomeWork: async () => ({ asOf: board.asOf, bucket: "all", sort: "importance", total: { count: sw.workCount, atLeast: 8 }, rows: [], nextCursor: null, sources: sw.workSources }),
    getChannelCoverageStrict: async () => [
      { channelCode: "COUPANG", channelNameKo: "쿠팡", dataType: "INQUIRY", state: "OBSERVED_FRESHNESS_UNPROVEN", supported: true, verificationStatus: null, connected: true, connectionStatus: "CONNECTED", routineEnabled: true, routinePausedBy: null, lastSuccessfulSyncAt: "2026-11-12T13:10:00Z", latestAttemptAt: "2026-11-13T01:00:00Z", latestAttemptOutcome: "AUTH_REQUIRED", rows: 3, openRows: null, newestObservedAt: null },
    ],
    getSellerAccountsStrict: async () => [{ id: "acc-cp", channelId: "c2", channelNameKo: "쿠팡", alias: null, connectionStatus: "CONNECTED", lastSyncedAt: null, fileUpload: false }],
    getOperationsHomeStrict: async () => ({ problems: { decidable: 0, observing: 0, dormant: 0, rows: [] }, collection: [] }),
    getCustomerOperationsHome: async () => ({ available: false }),
  },
}));

function renderHome() {
  return render(
    <MemoryRouter initialEntries={["/"]}>
      <CsHome now={new Date("2026-11-13T03:00:00Z")} />
    </MemoryRouter>,
  );
}

afterEach(() => {
  sw.overviewFails = false;
  sw.workSources = [];
  sw.workCount = 8;
});

describe("홈 — CS 현황", () => {
  it("every number opens the list it counts, under the same condition", async () => {
    renderHome();
    const tiles = await screen.findByRole("navigation", { name: "오늘 숫자" });
    // 오늘 접수: reviews left a channel out, so the sum is a floor; each half opens its own list for the same day.
    expect(await within(tiles).findByText("10+")).toBeInTheDocument();
    expect(within(tiles).getByRole("link", { name: "문의 4건" })).toHaveAttribute("href", "/inquiries?from=2026-11-13&to=2026-11-13");
    expect(within(tiles).getByRole("link", { name: "리뷰 6+건" })).toHaveAttribute("href", "/reviews?days=1");
    expect(within(tiles).getByRole("link", { name: /답할 일/ })).toHaveAttribute("href", "/work?work=answer");
    expect(within(tiles).getByText("리뷰 3건, 문의 3건")).toBeInTheDocument();
    expect(within(tiles).getByRole("link", { name: /처리 진행 중/ })).toHaveAttribute("href", "/work#기다리는-일");
    expect(within(tiles).getByRole("link", { name: /오늘 확인/ })).toHaveTextContent("3");
    expect(within(tiles).getByRole("link", { name: /오늘 확인/ })).toHaveAttribute("href", "/work?work=todayChecks");
  });

  it("the channel table names the cause and links the existing fix; nothing on it sends or starts", async () => {
    renderHome();
    const table = await screen.findByRole("table");
    const coupang = await within(table).findByTestId("cs-channel-COUPANG");
    expect(within(coupang).getByText("다시 연결 필요")).toBeInTheDocument();
    expect(within(coupang).getByRole("link", { name: "다시 연결" })).toHaveAttribute("href", "/connect/channels/acc-cp");
    expect(within(coupang).getByTitle("이 채널에서는 가져오지 않아요")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /보내|등록|승인/ })).toBeNull();
    expect(screen.getByTestId("cs-gap")).toHaveTextContent("쿠팡 다시 연결 필요");
  });

  it("on a phone each channel is one line — name, unanswered, state — and a collection problem shows while folded", async () => {
    renderHome();
    const card = await screen.findByTestId("cs-channel-card-COUPANG");
    // Folded: the problem's word is on the line itself, in the warning tone; the day's counts and the fix are not yet.
    expect(within(card).getByTestId("cs-channel-state")).toHaveTextContent("다시 연결 필요");
    expect(within(card).getByTestId("cs-channel-state")).toHaveClass("text-warn");
    const toggle = within(card).getByRole("button");
    expect(toggle).toHaveAttribute("aria-expanded", "false");
    expect(within(card).queryByTestId("cs-channel-more")).toBeNull();
    // Unfolded: the counts, the whole sentence and the existing fix — still a link, never a send.
    fireEvent.click(toggle);
    expect(toggle).toHaveAttribute("aria-expanded", "true");
    const more = within(card).getByTestId("cs-channel-more");
    expect(more).toHaveTextContent("오늘 문의");
    expect(more).toHaveTextContent("오늘 리뷰");
    expect(within(more).getByRole("link", { name: "다시 연결" })).toHaveAttribute("href", "/connect/channels/acc-cp");
  });

  it("on a phone the tiles' definitions fold behind one button — folded, never removed", async () => {
    renderHome();
    await screen.findByText("10+");
    const note = screen.getByText("확인하기로 한 날이 된 기록이에요");
    expect(note).toHaveClass("max-md:hidden");
    const button = screen.getByTestId("cs-tile-notes");
    expect(button).toHaveAttribute("aria-expanded", "false");
    fireEvent.click(button);
    expect(button).toHaveAttribute("aria-expanded", "true");
    expect(button).toHaveTextContent("숫자 설명 접기");
    expect(screen.getByText("확인하기로 한 날이 된 기록이에요")).not.toHaveClass("max-md:hidden");
    expect(screen.getByText("답변 등록이나 후속 처리가 남아 있어요")).not.toHaveClass("max-md:hidden");
  });

  it("an empty work list says so, and a clear list never claims more than was read", async () => {
    renderHome();
    expect(await screen.findByTestId("cs-first-empty")).toHaveTextContent("아직 확인하지 못한 자료에 남은 일이 있을 수 있어요");
  });

  it("an empty list read only in part never says the work is done", async () => {
    sw.workSources = [{ name: "CASES", read: true, complete: true }, { name: "REVIEWS", read: false, complete: false }];
    renderHome();
    const empty = await screen.findByTestId("cs-first-empty");
    expect(empty).toHaveTextContent("할 일을 다 불러오지 못했어요");
    expect(empty).not.toHaveTextContent("처리했어요");
  });

  it("an empty list whose total the server does not vouch for is not 「할 일 없음」 either", async () => {
    sw.workCount = null;
    renderHome();
    expect(await screen.findByTestId("cs-first-empty")).toHaveTextContent("할 일을 다 불러오지 못했어요");
  });

  it("a failed 새로고침 keeps the last numbers and says which part it could not refresh", async () => {
    renderHome();
    const tiles = await screen.findByRole("navigation", { name: "오늘 숫자" });
    expect(await within(tiles).findByText("10+")).toBeInTheDocument();
    expect(screen.queryByTestId("cs-stale")).toBeNull();
    sw.overviewFails = true;
    fireEvent.click(screen.getByTestId("cs-refresh"));
    const stale = await screen.findByTestId("cs-stale");
    expect(stale).toHaveTextContent("새로고침하지 못했어요: 오늘 숫자·채널별 숫자");
    expect(within(tiles).getByText("10+")).toBeInTheDocument();
    // The next refresh that lands clears it.
    sw.overviewFails = false;
    fireEvent.click(screen.getByTestId("cs-refresh"));
    await vi.waitFor(() => expect(screen.queryByTestId("cs-stale")).toBeNull());
  });
});
