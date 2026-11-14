// @vitest-environment jsdom
import { StrictMode } from "react";
import { describe, it, expect, vi, beforeEach } from "vitest";
import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";

const api = vi.hoisted(() => ({ getHomeBoard: vi.fn(), getHomeWork: vi.fn() }));
vi.mock("./apiClient", () => ({ api }));

import { useTodayWorkCount } from "./useTodayWorkCount";
import { useHomeBoard } from "./useHomeBoard";
import { announceTodayCount, isWorkWrite, noteWorkWrite, resetWorkChangesForTest } from "./workChanges";

const board = (count: number | null) => ({ todo: { total: { count, atLeast: count ?? 0 } } });

function Badge() {
  const n = useTodayWorkCount();
  return <span data-testid="badge">{n == null ? "none" : String(n)}</span>;
}

function draw() {
  return render(
    <MemoryRouter>
      <Badge />
    </MemoryRouter>,
  );
}

describe("the menu badge follows the Home and the seller's own presses (2026-11-10 review)", () => {
  beforeEach(() => {
    vi.resetAllMocks();
    resetWorkChangesForTest();
  });

  it("takes the Home's count the moment the Home reads its board — 새로고침 moves both together", async () => {
    api.getHomeBoard.mockResolvedValue(board(20));
    draw();
    await waitFor(() => expect(screen.getByTestId("badge")).toHaveTextContent("20"));
    act(() => announceTodayCount(19));
    expect(screen.getByTestId("badge")).toHaveTextContent("19");
    // The Home could not vouch for a number: neither does the badge.
    act(() => announceTodayCount(null));
    expect(screen.getByTestId("badge")).toHaveTextContent("none");
  });

  it("a write the seller made re-reads it at once, inside the window a plain move between screens waits out", async () => {
    api.getHomeBoard.mockResolvedValue(board(20));
    draw();
    await waitFor(() => expect(screen.getByTestId("badge")).toHaveTextContent("20"));
    expect(api.getHomeBoard).toHaveBeenCalledTimes(1);
    api.getHomeBoard.mockResolvedValue(board(19));
    act(() => noteWorkWrite());
    await waitFor(() => expect(screen.getByTestId("badge")).toHaveTextContent("19"));
    expect(api.getHomeBoard).toHaveBeenCalledTimes(2);
  });

  it("only an exact count is a badge", async () => {
    api.getHomeBoard.mockResolvedValue(board(null));
    draw();
    await waitFor(() => expect(api.getHomeBoard).toHaveBeenCalled());
    expect(screen.getByTestId("badge")).toHaveTextContent("none");
  });

  it("counts a write, never a read, a sign-in or the screen-change question", () => {
    expect(isWorkWrite("post", "/api/inquiries/i-1/follow-up")).toBe(true);
    expect(isWorkWrite("put", "/api/reviews/r-1/reply")).toBe(true);
    expect(isWorkWrite("get", "/api/operations/home-board")).toBe(false);
    expect(isWorkWrite("post", "/api/auth/login")).toBe(false);
    expect(isWorkWrite("post", "/api/live/screen-changes")).toBe(false);
  });
});

/* ---- A slow earlier read never overwrites a newer answer (2026-11-10 review) ---- */

type Deferred = { resolve: (v: unknown) => void; reject: (e: unknown) => void };

/** Every board read waits until the test answers it, in whatever order the test chooses. */
function holdReads(): Deferred[] {
  const calls: Deferred[] = [];
  api.getHomeBoard.mockImplementation(
    () =>
      new Promise((resolve, reject) => {
        calls.push({ resolve, reject });
      }),
  );
  return calls;
}

const late = [
  ["success", (d: Deferred) => d.resolve(board(20))],
  ["failure", (d: Deferred) => d.reject(new Error("slow and down"))],
] as const;

function Home() {
  const state = useHomeBoard();
  const n = state.board === undefined ? "reading" : state.board === null ? "failed" : String(state.board.todo.total.count);
  return (
    <>
      <span data-testid="home">{n}</span>
      <button onClick={state.reload}>새로고침</button>
    </>
  );
}

describe("the newest read wins, not the last to arrive", () => {
  beforeEach(() => {
    vi.resetAllMocks();
    resetWorkChangesForTest();
    api.getHomeWork.mockResolvedValue({ rows: [], nextCursor: null, total: { count: 0, atLeast: 0 } });
  });

  it.each(late)("badge: earlier read slow → newer read answers 19 → the earlier %s lands, and the badge stays 19", async (_, land) => {
    const calls = holdReads();
    draw();
    await waitFor(() => expect(calls).toHaveLength(1)); // read 1 — started before the press, still out
    act(() => noteWorkWrite());
    await waitFor(() => expect(calls).toHaveLength(2)); // read 2 — after the press
    await act(async () => calls[1].resolve(board(19)));
    expect(screen.getByTestId("badge")).toHaveTextContent("19");

    await act(async () => land(calls[0]));
    expect(screen.getByTestId("badge")).toHaveTextContent("19");
  });

  it.each(late)("Home and badge: the Home's read before 새로고침 slow → the new one answers 19 → the old %s lands, and both stay 19", async (_, land) => {
    const calls = holdReads();
    render(
      <MemoryRouter>
        <Home />
        <Badge />
      </MemoryRouter>,
    );
    await waitFor(() => expect(calls).toHaveLength(2)); // [0] the Home's first read, [1] the badge's
    await act(async () => calls[1].resolve(board(20)));
    expect(screen.getByTestId("badge")).toHaveTextContent("20");

    await userEvent.click(screen.getByRole("button", { name: "새로고침" }));
    await waitFor(() => expect(calls).toHaveLength(3));
    await act(async () => calls[2].resolve(board(19)));
    expect(screen.getByTestId("home")).toHaveTextContent("19");
    expect(screen.getByTestId("badge")).toHaveTextContent("19");

    await act(async () => land(calls[0]));
    expect(screen.getByTestId("home")).toHaveTextContent("19");
    expect(screen.getByTestId("badge")).toHaveTextContent("19");
  });

  it("a newer failure is still the newest answer: no badge, even when an older read had a count", async () => {
    const calls = holdReads();
    draw();
    await waitFor(() => expect(calls).toHaveLength(1));
    act(() => noteWorkWrite());
    await waitFor(() => expect(calls).toHaveLength(2));
    await act(async () => calls[1].reject(new Error("down")));
    await act(async () => calls[0].resolve(board(20)));
    expect(screen.getByTestId("badge")).toHaveTextContent("none");
  });

  it("React's double mount in development loses neither the Home's first read nor the badge's", async () => {
    const calls = holdReads();
    render(
      <StrictMode>
        <MemoryRouter>
          <Home />
          <Badge />
        </MemoryRouter>
      </StrictMode>,
    );
    // The Home reads twice (mount, re-mount); the badge reads once and does not drop it on the re-mount.
    await waitFor(() => expect(calls).toHaveLength(3));
    await act(async () => calls.forEach((c) => c.resolve(board(20))));
    expect(screen.getByTestId("home")).toHaveTextContent("20");
    expect(screen.getByTestId("badge")).toHaveTextContent("20");
  });
});
