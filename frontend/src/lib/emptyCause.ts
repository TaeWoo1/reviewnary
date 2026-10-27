import { useEffect, useState } from "react";
import { api } from "./apiClient";
import type { ChannelCoverageRowView } from "./types";

/**
 * <b>Why a list is empty — three causes, three sentences</b> (2026-10-28).
 *
 * <p>문의 and 반복 문제 said 「채널 연동하기」 to a seller with two channels connected, because an empty list had one
 * sentence for every cause. The coverage read already says which one it is:
 * <ul>
 *   <li>{@code NOT_CONNECTED} — no channel that carries this data is connected;</li>
 *   <li>{@code NOT_FETCHED} — one is, and nothing of this kind has been read from it yet;</li>
 *   <li>{@code NONE} — it was read, and there is nothing.</li>
 * </ul>
 * Unknown (the read did not land) is {@code null}: the screen then keeps its older, cause-free sentence.
 */
export type EmptyCause = "NOT_CONNECTED" | "NOT_FETCHED" | "NONE";

export function emptyCause(
  collection: readonly ChannelCoverageRowView[] | null | undefined,
  dataType: "INQUIRY" | "REVIEW",
): EmptyCause | null {
  if (!collection) return null;
  const rows = collection.filter((row) => row.dataType === dataType && row.connected);
  if (rows.length === 0) return "NOT_CONNECTED";
  return rows.some((row) => row.lastSuccessfulSyncAt != null || row.rows > 0) ? "NONE" : "NOT_FETCHED";
}

/** The coverage read for an empty state: undefined while loading, null when it failed. */
export function useCoverage(): ChannelCoverageRowView[] | null | undefined {
  const [rows, setRows] = useState<ChannelCoverageRowView[] | null | undefined>(undefined);
  useEffect(() => {
    let live = true;
    // Through a promise, so a client without this read (an older build, a test double) is a failed read — null, the
    // older cause-free sentence — and never a thrown render.
    Promise.resolve()
      .then(() => api.getChannelCoverageStrict())
      .then((next) => {
        if (live) setRows(next);
      })
      .catch(() => {
        if (live) setRows(null);
      });
    return () => {
      live = false;
    };
  }, []);
  return rows;
}
