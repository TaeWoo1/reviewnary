// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, useLocation } from "react-router-dom";
import type { ServerWorkRow } from "../../lib/homeBoard";

const api = vi.hoisted(() => ({ getHomeBoard: vi.fn(), getHomeWork: vi.fn() }));
vi.mock("../../lib/apiClient", () => ({ api, getToken: () => null }));

import { HomeWorkList } from "./HomeWorkList";
import { useHomeBoard } from "../../lib/useHomeBoard";
import { boardFrom, pageFrom, serveBoard } from "../../test/homeBoardFake";

const NOW = new Date("2026-11-10T04:00:00Z");

function inquiry(id: string, over: Partial<ServerWorkRow> = {}): ServerWorkRow {
  return {
    key: `inquiry:${id}`, owner: `/inquiries/${id}`, to: `/inquiries/${id}`, kind: "INQUIRY", subject: "INQUIRY",
    subjectId: id, caseId: null, workItemId: `w-${id}`, state: "REPLY_NEEDED", delegatedState: null, bucket: "needsReply",
    past: false, praise: false, safety: false, safetyReading: null, safetyKind: null, channelCode: "CAFE24",
    channelNameKo: "카페24", rating: null, title: `합성 문의 ${id}`, context: null, since: "2026-11-10T02:00:00Z",
    dueToday: false, nextCheckOn: null, also: [], origin: null, ...over,
  };
}

const CHECK = {
  action: "CHECK_AGAIN" as const, workItemId: "w-old", checkId: "chk-1", waitingOn: "ACTION", nextCheckOn: "2026-11-10",
  dueToday: true, since: "2026-11-07T01:00:00Z", note: "합성-진행 기록", to: "/inquiries/old",
};

const ROWS: ServerWorkRow[] = [
  inquiry("new"),
  inquiry("alone", { key: "follow:alone", state: "CHECK_AGAIN", bucket: "checkAgain", dueToday: true }),
  inquiry("old", { since: "2026-10-21T01:00:00Z", dueToday: true, also: [CHECK] }),
];

function Where() {
  const { search } = useLocation();
  return <output data-testid="where">{search}</output>;
}

function Board() {
  const state = useHomeBoard();
  return <HomeWorkList state={state} selection={{ wide: false, selectedKey: null, search: "" }} now={NOW} emptySentence="지금 할 일이 없어요." />;
}

function draw(path = "/") {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Board />
      <Where />
    </MemoryRouter>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
});

describe("「오늘 확인 모두」 — a view across the buckets", () => {
  it("opens every row with a check due today, marks the riding check, and comes back to the whole list", async () => {
    serveBoard(api, ROWS);
    draw();
    const chip = await screen.findByTestId("chip-today-checks");
    expect(chip).toHaveTextContent("오늘 확인 모두 2 ›");
    // The breakdown line moved to the band's 할 일 cell (2026-11-10); its words are `todoBreakdown`'s (homeBoard.test).

    await userEvent.click(chip);
    await waitFor(() => expect(api.getHomeWork).toHaveBeenLastCalledWith(expect.objectContaining({ bucket: "todayChecks", cursor: null })));
    expect(screen.getByTestId("where")).toHaveTextContent("?work=todayChecks");
    const head = await screen.findByTestId("home-work-view-head");
    expect(head).toHaveTextContent("오늘 확인할 기록 2건");
    expect(head).toHaveTextContent("다시 확인 1건 + 다른 할 일 안에 있는 확인 1건");
    const list = await screen.findByRole("list", { name: /오늘 확인할 기록/ });
    await waitFor(() => expect(within(list).getAllByTestId("attached-check")).toHaveLength(1));
    expect(within(list).getByTestId("attached-check").className).toContain("ring-warn");
    expect(within(list).queryByText("합성 문의 new")).toBeNull();

    await userEvent.click(screen.getByTestId("home-work-back"));
    await waitFor(() => expect(screen.getByTestId("where")).toHaveTextContent(/^$/));
    expect(screen.queryByTestId("home-work-view-head")).toBeNull();
  });

  it("stands only when a check rides on another row — otherwise 다시 확인 alone says it", async () => {
    serveBoard(api, ROWS.slice(0, 2));
    draw();
    await screen.findByTestId("chip-checkAgain");
    expect(screen.queryByTestId("chip-today-checks")).toBeNull();
  });

  it("the riding check is its own control, outside the row's link, and opens the inquiry on that record", async () => {
    serveBoard(api, ROWS);
    draw();
    const check = await screen.findByTestId("attached-check");
    const go = within(check).getByRole("link", { name: "진행 결과 적기 ›" });
    expect(go).toHaveAttribute("href", "/inquiries/old?focus=seller-check&checkId=chk-1");
    expect(go.closest("a")?.parentElement?.closest("a")).toBeNull();
    expect(check).toHaveTextContent("11월 10일이 다시 확인할 날 · 11월 7일 「접수·진행 중」 기록");
  });
});

describe("a failed read is not an empty list", () => {
  it("says it could not read, offers to read again, and reads again on the press", async () => {
    api.getHomeBoard.mockRejectedValue(new Error("down"));
    api.getHomeWork.mockRejectedValue(new Error("down"));
    draw();
    const failed = await screen.findByTestId("home-work-failed");
    expect(failed).toHaveTextContent("할 일 목록을 불러오지 못했어요. 할 일이 없다는 뜻은 아니에요.");
    expect(screen.queryByTestId("home-work-empty")).toBeNull();
    expect(screen.getByTestId("chip-all")).toHaveTextContent("전체 —");

    serveBoard(api, ROWS);
    await userEvent.click(within(failed).getByRole("button", { name: "다시 불러오기" }));
    expect(await screen.findByText("합성 문의 new")).toBeInTheDocument();
  });

  it("an empty list read whole says there is nothing — and only then", async () => {
    serveBoard(api, []);
    draw();
    expect(await screen.findByTestId("home-work-empty")).toHaveTextContent("지금 할 일이 없어요.");
    expect(screen.queryByTestId("home-work-failed")).toBeNull();
  });

  it("a count it could not confirm stays 「—」 while the rows it did read stay listed", async () => {
    const board = boardFrom(ROWS, {
      complete: false,
      sources: [
        { name: "CASES", read: true, complete: false }, { name: "REVIEWS", read: true, complete: true },
        { name: "INQUIRIES", read: true, complete: true }, { name: "FOLLOW_UPS", read: true, complete: true },
      ],
    });
    board.todo.total = { count: null, atLeast: 0 };
    board.todo.needsReply = { count: null, atLeast: 0 };
    api.getHomeBoard.mockResolvedValue(board);
    api.getHomeWork.mockImplementation(async (p) => ({ ...pageFrom(ROWS, board, p), total: { count: null, atLeast: 0 } }));
    draw();
    expect(await screen.findByTestId("home-work-partial")).toHaveTextContent("이미 처리된 일인지 다 확인하지 못했어요");
    expect(screen.getByTestId("chip-all")).toHaveTextContent("전체 —");
    expect(await screen.findByText("합성 문의 new")).toBeInTheDocument();
    expect(screen.getByTestId("home-work-pager")).toHaveTextContent("1–3 · 전체 —");
  });
});

describe("the order is the server's and the seller picks it", () => {
  it("changing the sort asks the server again from the first page", async () => {
    serveBoard(api, ROWS);
    draw();
    await screen.findByText("합성 문의 new");
    await userEvent.selectOptions(screen.getByTestId("home-work-sort"), "longest");
    await waitFor(() => expect(api.getHomeWork).toHaveBeenLastCalledWith(expect.objectContaining({ sort: "longest", cursor: null })));
    expect(screen.getByTestId("where")).toHaveTextContent("?workSort=longest");
  });
});

describe("지난 일 keeps its way in when its count is not confirmed (2026-11-10 review)", () => {
  it("an unconfirmed 지난 일 is 「—」, never 0, and still opens the 지난 일 view", async () => {
    const past = inquiry("past", { past: true, since: "2026-10-01T01:00:00Z" });
    serveBoard(api, [inquiry("new"), past], { past: { total: { count: null, atLeast: 0 }, lowRatedReviews: { count: null, atLeast: 0 } } });
    draw();
    const line = await screen.findByTestId("home-work-past");
    expect(line).toHaveTextContent("지난 일 수는 다 확인하지 못했어요");
    await userEvent.click(within(line).getByRole("button", { name: "지난 일 보기 ›" }));
    await waitFor(() => expect(screen.getByTestId("where")).toHaveTextContent("?work=past"));
    expect(await screen.findByRole("link", { name: /합성 문의 past/ })).toBeInTheDocument();
  });

  it("a measured zero has nothing to open", async () => {
    serveBoard(api, [inquiry("new")]);
    draw();
    await screen.findByTestId("home-work-pager");
    expect(screen.queryByTestId("home-work-past")).toBeNull();
  });
});
