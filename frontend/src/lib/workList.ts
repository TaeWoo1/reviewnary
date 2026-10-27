import { useEffect, useState } from "react";
import { api } from "./apiClient";
import { mergeHomeWork, workBucket, type HomeWork, type HomeWorkRow } from "./homeWork";

/** How many inquiry rows the list asks for — the Home's and the queue's slice. */
export const WORK_QUEUE_SIZE = 50;

/**
 * <b>할 일, read the way the Home reads it</b> — the four reads, merged once. Null when none of them landed.
 *
 * <p>Each read goes through a promise so a client without one (an older build, a test double) is a failed read and
 * not a thrown render.
 */
export async function loadWork(now: Date = new Date()): Promise<HomeWork | null> {
  const quiet = <T,>(read: () => Promise<T>) => Promise.resolve().then(read).catch(() => null);
  const [co, ops, queue, reviewWork] = await Promise.all([
    quiet(() => api.getCustomerOperationsHome()),
    quiet(() => api.getOperationsHomeStrict()),
    quiet(() => api.getInquiryQueueStrict({ size: WORK_QUEUE_SIZE })),
    quiet(() => api.getReviewWorkStrict()),
  ]);
  if (!co && !ops && !queue && !reviewWork) return null;
  return mergeHomeWork(co, ops, queue, now, reviewWork);
}

export interface WorkNeighbours {
  prev: HomeWorkRow | null;
  next: HomeWorkRow | null;
  /** 1-based place of this item among its bucket's rows, and how many there are. */
  position: number;
  total: number;
}

/**
 * <b>The item before and after this one, in 할 일's own order</b> (2026-10-28 — 「확정하고 다음」 and J/K). Forty-nine
 * replies waiting for 확정 were worked by going back to the list forty-nine times. The neighbours are taken from the
 * same bucket (확정 전 next to 확정 전, 판단 필요 next to 판단 필요), so 「다음」 is the next thing of the same kind.
 *
 * <p>Null while loading, and when this item is not in 할 일 — a review opened from the record has no 「다음」.
 */
export function useWorkNeighbours(owner: string | null): WorkNeighbours | null {
  const [work, setWork] = useState<HomeWork | null>(null);
  useEffect(() => {
    if (!owner) return;
    let live = true;
    void loadWork().then((next) => {
      if (live) setWork(next);
    });
    return () => {
      live = false;
    };
  }, [owner]);
  if (!owner || !work) return null;
  return neighboursIn(work.rows, owner);
}

/** {@link useWorkNeighbours}' rule, pure: the rows of this item's bucket, in the list's order. */
export function neighboursIn(rows: readonly HomeWorkRow[], owner: string): WorkNeighbours | null {
  const here = rows.find((r) => r.owner === owner);
  if (!here) return null;
  const bucket = workBucket(here);
  const same = rows.filter((r) => workBucket(r) === bucket);
  const at = same.indexOf(here);
  return {
    prev: at > 0 ? same[at - 1]! : null,
    next: at < same.length - 1 ? same[at + 1]! : null,
    position: at + 1,
    total: same.length,
  };
}
