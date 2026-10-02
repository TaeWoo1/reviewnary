import { kstDayKey } from "./homeSummary";
import type { OperationsInsight } from "./types";

/**
 * <b>「오늘 달라진 점」 means a change proven by comparing two windows</b> (product-owner decision,
 * 2026-10-01).
 *
 * <p>Two wrong answers were tried before this one, and naming both is the point of the file:
 *
 * <ol>
 *   <li><b>No filter at all.</b> The Home drew `insights[0]` whatever it was, and on the live org
 *       that was a five-month lifetime roll-up — 「부정 리뷰 3건 / 2025-11-01 ~ 2026-03-06」 — under a
 *       heading asking what changed this morning.</li>
 *   <li><b>Filtering on {@code observedAt === today}.</b> Closer, and still wrong: observation is not
 *       change. A connector disconnected three weeks ago is observed to be disconnected again every
 *       time we read, so that rule put 「G마켓/옥션 연결이 끊겼습니다」 under 오늘 달라진 점 — a standing
 *       state wearing today's date, about a channel this product does not even connect.</li>
 * </ol>
 *
 * <p>So the gate is structural rather than temporal: <b>an insight qualifies when it states both its
 * own window and the window it was compared against</b> (최근 N일 vs 이전 N일 — `MetricPeriod`'s own
 * shape). A producer that measures one window and no baseline is stating a level, and a level is not
 * news however recently it was measured. The backend sets all four fields on exactly the producers
 * whose claim is a movement.
 *
 * <p><b>Collection health is not here and must not be.</b> A broken connector, a failed sync and a
 * stale collection are owned by the Home's 채널 상태 section alone — it is the question 「채널은
 * 정상인가」, asked at the foot of the screen, and answering it twice in two voices is how one of them
 * ends up reassuring a seller the other is warning.
 *
 * <p>The window must also still be the current one. {@code periodEnd} is `LocalDate.now(KST)` when
 * the metrics are computed, so a response held from yesterday has an older one — the same guard
 * {@code todayInflow} applies to its series, and it reads a window BOUNDARY rather than an
 * observation time.
 *
 * <p><b>Nothing is reinterpreted and nothing is invented.</b> This filters; it does not re-date,
 * re-rank or re-word. What it drops is still true and still reachable — it is the overview's, which
 * owns every window longer than today and every lifetime aggregate, and the section's own
 * 「전체 보기」 is the way there.
 */
export function changeInsights(
  insights: OperationsInsight[] | null | undefined,
  now: Date,
): OperationsInsight[] | null {
  if (!insights) return null;
  const today = kstDayKey(now);
  return insights.filter(
    (insight) =>
      insight.periodStart !== null &&
      insight.periodEnd !== null &&
      insight.previousPeriodStart !== null &&
      insight.previousPeriodEnd !== null &&
      insight.periodEnd === today,
  );
}

/**
 * What the section says when the read landed and no window comparison showed a change.
 *
 * <p>Deliberately not the section disappearing. 「변화가 없다」 and 「우리가 보지 않았다」 are different
 * facts and a seller cannot tell them apart from an absence — the same reason 반복 문제 stopped
 * rendering nothing. This string is only ever printed when the insights read SUCCEEDED; a failed
 * read (`null`) still draws nothing, because then we do not know.
 *
 * <p>It ends in a full stop because it is the section's whole BODY now, not a fragment beside other
 * clauses — the same shape as 「이미 결정한 일입니다. 등록만 남았습니다.」 one section down.
 */
export const NO_CHANGE_TODAY = "오늘 새로 확인된 변화는 없습니다.";
