import type { ChannelCoverageRowView } from "./types";

/**
 * <b>「마지막으로 가져온 때」 — one answer per channel, read from one place</b> (2026-10-28).
 *
 * <p>The Home read the coverage rows' `lastSuccessfulSyncAt` and said 「쿠팡 가져온 적 없음」; 연동 read the connection's
 * own `lastSyncedAt` and said 「연동됨 · 10월 25일 16:38」 for the same channel on the same morning. Only the first is
 * a collection that actually read data, so both screens ask this.
 *
 * <p>The coverage read is one row per (channel × data type); a channel is as recently read as its most recently read
 * data type. A channel whose every row has never succeeded maps to null — 「가져온 적 없음」 — and keeps its key, so a
 * screen can tell 「never」 from 「not in the read」.
 */
export function lastCollectedByChannel(collection: readonly ChannelCoverageRowView[]): Map<string, number | null> {
  const latest = new Map<string, number | null>();
  for (const row of collection) {
    const at = row.lastSuccessfulSyncAt ? new Date(row.lastSuccessfulSyncAt).getTime() : NaN;
    const value = Number.isNaN(at) ? null : at;
    const seen = latest.get(row.channelCode);
    if (seen === undefined) latest.set(row.channelCode, value);
    else if (value !== null && (seen === null || value > seen)) latest.set(row.channelCode, value);
  }
  return latest;
}
