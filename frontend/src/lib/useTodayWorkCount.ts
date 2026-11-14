import { useEffect, useRef } from "react";
import { useLocation } from "react-router-dom";
import { api } from "./apiClient";
import { useScreenChanges } from "./screenChanges";
import { beginTodayRead, useTodayCount, useWorkChanges } from "./workChanges";

/** A move between screens re-reads the count, but not more often than this. */
const REREAD_MS = 20_000;

/**
 * <b>The menu's 할 일 badge</b> (product-owner decision, 2026-10-28: 할 일 / 리뷰·문의 / 상품·정책, with the count on
 * 할 일). Since 2026-11-10 it is the Home's own number — the server board's 할 일 (`GET /api/operations/home-board`) — so
 * the badge cannot say a number the Home does not.
 *
 * <p><b>It follows the Home, and the seller's own presses</b> (2026-11-10 review). Whenever the Home reads its board the
 * badge takes that count at once, so 새로고침 and a processed row on the Home move both together. A write the seller made
 * anywhere ({@link useWorkChanges}) and a change the server announced re-read it straight away; only a plain move
 * between screens is held to {@link REREAD_MS}.
 *
 * <p><b>The newest read, not the last to land.</b> A read that began earlier and answers later is out of date and is
 * dropped, success or failure — see {@link beginTodayRead}.
 *
 * <p>Only an exact count is a badge. A count the server could not confirm (「N+」 or 「—」 on the Home) and a failed read are
 * both an absent badge, never a zero and never a guess.
 */
export function useTodayWorkCount(): number | null {
  const { pathname } = useLocation();
  const writes = useWorkChanges();
  const pushed = useScreenChanges();
  const latest = useTodayCount();
  const lastRead = useRef(0);
  const lastSignal = useRef(`${writes}|${pushed}`);

  useEffect(() => {
    const signal = `${writes}|${pushed}`;
    const signalled = signal !== lastSignal.current;
    lastSignal.current = signal;
    // Marked when a read STARTS and never cancelled: a cleanup that dropped the answer would, under React's double
    // mount in development, leave the second mount inside the re-read window with nothing to show.
    if (!signalled && Date.now() - lastRead.current < REREAD_MS) return;
    lastRead.current = Date.now();
    const settle = beginTodayRead();
    void Promise.resolve()
      .then(() => api.getHomeBoard())
      .then((board) => settle(board.todo.total.count ?? null))
      .catch(() => settle(null));
  }, [pathname, writes, pushed]);

  // One answer for every reader: the newest read that has answered, whichever screen made it.
  return latest?.count ?? null;
}
