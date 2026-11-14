import { useSyncExternalStore } from "react";

/**
 * <b>The seller changed something here, so 할 일 may have moved</b> (2026-11-10 review: 「같은 화면에서 업무를 처리하거나
 * 새로고침해도 메뉴 배지가 이전 숫자로 남을 수 있다」).
 *
 * <p>The server tells open screens when the PC stores an upload ({@link useScreenChanges}); it does not tell them when
 * the seller's own press on this screen approved, recorded or set aside something. So every write that succeeds through
 * the API client is noted here, a few in a row as one, and the reads that draw 할 일 — the Home's board and the menu
 * badge — read again. It says only that something was written; what changed is the server's to answer.
 */
const COALESCE_MS = 300;

let version = 0;
let timer: ReturnType<typeof setTimeout> | null = null;
const listeners = new Set<() => void>();

function emit() {
  for (const listener of [...listeners]) listener();
}

/** A write succeeded. Several in quick succession are one change. */
export function noteWorkWrite(): void {
  if (timer) return;
  timer = setTimeout(() => {
    timer = null;
    version += 1;
    emit();
  }, COALESCE_MS);
}

/** Which writes count: anything that is not a sign-in and not the screen-change question itself. */
export function isWorkWrite(method: string | undefined, url: string | undefined): boolean {
  const m = (method ?? "get").toLowerCase();
  if (m === "get" || m === "head" || m === "options") return false;
  const path = url ?? "";
  return !/\/api\/auth\//.test(path) && !/screen-changes/.test(path);
}

function subscribe(listener: () => void) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

/** How many writes this app has noted; moves when the seller's own press may have changed 할 일. */
export function useWorkChanges(): number {
  return useSyncExternalStore(subscribe, () => version, () => 0);
}

/* ---- The 할 일 count, one answer for the Home and the badge ---- */

/**
 * <b>The newest read wins, not the last to arrive</b> (2026-11-10 review: 「이전 조회가 늦게 끝나도 최신 집계와 배지를 덮어쓰지
 * 않게」). The Home and both badges (side menu, bottom bar) each read the board; each read takes a number when it STARTS,
 * and its answer — a count, or `null` for a failure or a count the server could not confirm — is kept only if no read
 * that started after it has answered already. A slow read that began before a press can therefore never put back the
 * number from before the press, and a slow failure can never blank a newer count.
 */
type TodayCount = { count: number | null; seq: number };

let started = 0;
let settled = 0;
let latest: TodayCount | null = null;
const countListeners = new Set<() => void>();

/** A read of the board is starting. Call the returned function with its answer — `null` when it failed or is unsure. */
export function beginTodayRead(): (count: number | null) => void {
  const seq = ++started;
  return (count) => {
    if (seq <= settled) return; // a newer read has answered: this one is out of date
    settled = seq;
    latest = { count, seq };
    for (const listener of [...countListeners]) listener();
  };
}

/** A read that started and answered now — for a caller that holds an answer it knows is the newest (tests). */
export function announceTodayCount(count: number | null): void {
  beginTodayRead()(count);
}

/** The newest answered 할 일 count; `null` until something answered. */
export function useTodayCount(): TodayCount | null {
  return useSyncExternalStore(
    (listener) => {
      countListeners.add(listener);
      return () => countListeners.delete(listener);
    },
    () => latest,
    () => null,
  );
}

/** Tests only: forget what was noted. */
export function resetWorkChangesForTest(): void {
  version = 0;
  started = 0;
  settled = 0;
  latest = null;
  if (timer) clearTimeout(timer);
  timer = null;
}
