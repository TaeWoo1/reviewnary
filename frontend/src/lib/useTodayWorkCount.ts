import { useEffect, useRef, useState } from "react";
import { useLocation } from "react-router-dom";
import { todayWork } from "./homeWork";
import { loadWork } from "./workList";

/** A move between screens re-reads the count, but not more often than this. */
const REREAD_MS = 20_000;

/**
 * <b>The menu's 할 일 badge</b> (product-owner decision, 2026-10-28: 할 일 / 리뷰·문의 / 상품·정책, with the count on
 * 할 일). The same four reads and the same two functions as the Home and the queue — `mergeHomeWork`, then
 * `todayWork` — so the badge cannot say a number neither screen says. Null until the reads land, and when none of
 * them did: an absent badge, never a zero.
 */
export function useTodayWorkCount(): number | null {
  const { pathname } = useLocation();
  const [count, setCount] = useState<number | null>(null);
  const lastRead = useRef(0);

  useEffect(() => {
    // Marked when a read STARTS and never cancelled: a cleanup that dropped the answer would, under React's double
    // mount in development, leave the second mount inside the re-read window with nothing to show.
    if (Date.now() - lastRead.current < REREAD_MS) return;
    lastRead.current = Date.now();
    void loadWork().then((work) => {
      if (!work) return;
      setCount(todayWork(work.rows, new Date()).length);
    });
  }, [pathname]);

  return count;
}
