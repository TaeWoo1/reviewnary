/**
 * <b>Home Operations Summary — 오늘 들어온 것 → 지금 볼 것 → 최근 24시간.</b>
 *
 * <p>The contract this implements is `docs/pilot_usage_loop_v1.md` §8. It adds <b>no read</b>: every
 * value here comes from the five the Home already makes. What it adds is the one thing that decides
 * whether a number may be spoken at all — <b>a measured zero and an unobserved zero are different
 * facts</b>, and only the first one is allowed to print as 「0」.
 *
 * <p>Nothing here reinterprets a record. 「오늘 처리 완료」 does not exist, because no canonical
 * completion event spans review and inquiry (§8-3); this module therefore never names one.
 */

import type { CustomerOperationsHome } from "./customerOperationsTypes";
import type { OperationsMetrics } from "./types";

const KST = "Asia/Seoul";

/** `2026-09-26` as Korea reads it. The series' day keys are KST dates, so the comparison must be too. */
export function kstDayKey(now: Date): string {
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: KST,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).format(now);
  return parts;
}

/**
 * One inflow number, or the refusal to state one.
 *
 * <p>`UNQUALIFIED` is not 「0」 and must never render as one: it means we could not establish that
 * every channel able to contribute today was actually read.
 */
export type InflowFact = { kind: "COUNT"; value: number } | { kind: "UNQUALIFIED" };

export interface TodayInflow {
  reviews: InflowFact;
  inquiries: InflowFact;
  /**
   * The window was computed over rows the product manufactured about itself
   * (`OperationsMetricsResponse.exampleDataIncluded`). The figures are real numbers of synthetic rows,
   * so they may be shown — but never unlabelled, or a DEMO_SEED count reads as the seller's own.
   */
  exampleData: boolean;
}

/**
 * Today's arrivals, per metric, from the read the Home already makes (`getOverviewStrict(7)`).
 *
 * <p><b>The series is dense and its upper bound is today in Korea time</b>, so today's value is its
 * last point — no second call and no `days=1` read. Three things have to hold before that point may
 * be spoken, and they are checked per metric because freshness is per data type: a seller whose
 * inquiries are current and whose reviews are not is entitled to the number we do have.
 *
 * <ol>
 *   <li>the series and its KPI both exist;</li>
 *   <li>the last point really is <b>today</b> — a response held from yesterday must not be read as
 *       this morning;</li>
 *   <li>the KPI says every channel counted and none of them was unproven
 *       (`excludedChannels === 0 && !freshnessUnproven`). That is exactly the condition under which
 *       the series' all-channel sum and the KPI's qualified population are the same set.</li>
 * </ol>
 *
 * <p>`null` when the read did not land: a failed request says nothing, it does not say zero.
 */
export function todayInflow(metrics: OperationsMetrics | null | undefined, now: Date): TodayInflow | null {
  if (!metrics) return null;
  const today = kstDayKey(now);
  const factOf = (key: string): InflowFact => {
    const series = metrics.series.find((s) => s.key === key);
    const kpi = metrics.kpis.find((k) => k.key === key);
    if (!series || !kpi) return { kind: "UNQUALIFIED" };
    const last = series.points[series.points.length - 1];
    if (!last || last.date !== today) return { kind: "UNQUALIFIED" };
    if (kpi.excludedChannels !== 0 || kpi.freshnessUnproven) return { kind: "UNQUALIFIED" };
    return { kind: "COUNT", value: last.value };
  };
  return { reviews: factOf("reviews"), inquiries: factOf("inquiries"), exampleData: metrics.exampleDataIncluded };
}

/**
 * The three figures of `handled` that actually share the 24-hour window, and only those.
 *
 * <p>`monitoring` and `verifying` are deliberately absent: they are <b>point-in-time</b> counts of
 * currently-open cases, and putting them under a 「최근 24시간」 heading would attach a window to a
 * number that has none (§8-4a). `verifying` belongs beside 「지금 볼 것」, where it is a present fact.
 *
 * <p>Even these three are windowed on the case's <b>opening</b> time, not on when anything was
 * handled — so the words above them may say 「새로 확인」, never 「처리 완료」 (§8-4b).
 *
 * <p>`null` when the org has no window yet (no responsibility row) or the server did not send the
 * denominator: there is nothing true to say, and 「0」 would be a claim.
 */
export function recentDay(
  co: CustomerOperationsHome | null | undefined,
): { checked: number; autoResolved: number; draftsPrepared: number } | null {
  if (!co || co.handled.since == null || co.handled.checked == null) return null;
  return {
    checked: co.handled.checked,
    autoResolved: co.handled.autoResolved,
    draftsPrepared: co.handled.draftsPrepared,
  };
}

/**
 * The label each inflow metric carries, qualified or not. The number is rendered separately.
 *
 * <p>`lead` is 「오늘 들어온 것」, not 「오늘 들어옴」: the second is a nominalised verb and reads as a log
 * entry (처리됨 · 완료됨). Every other name on this screen is a noun phrase of exactly this shape —
 * 확인할 일 · 실행 대기 · 반복 문제 — and the summary is not the one place that talks differently.
 */
export const INFLOW_WORD = {
  lead: "오늘 들어온 것",
  reviews: "리뷰",
  inquiries: "문의",
  reviewsUnqualified: "리뷰 수집 확인 필요",
  inquiriesUnqualified: "문의 수집 확인 필요",
  /**
   * Both lanes withheld. Rendered <b>instead of</b> the two lane sentences, which are the same five
   * syllables twice — and the claim is identical either way: we cannot vouch for today's arrivals.
   * The moment one lane qualifies this collapses back, because then the two lanes differ and saying
   * so is the whole point.
   */
  bothUnqualified: "수집 상태 확인 필요",
  exampleData: "예시 데이터",
} as const;

/**
 * <b>현재 미답변 — 고객이 아직 답을 받지 못한 문의.</b>
 *
 * <p><b>Why it stands beside 확인할 일 rather than inside it</b> (product-owner decision, 2026-09-30).
 * They are two different sets and they answer two different questions. 확인할 일 is OUR queue — what is
 * waiting for the seller's decision, assembled by `mergeHomeWork`. 미답변 is the CHANNEL's fact — an
 * inquiry with no answer posted against it, whether or not we ever opened a work item for it. An inquiry
 * outside `InquiryRowsService.WORKABLE` is 미답변 and is not 확인할 일, which is exactly the gap the
 * 문의 screen's 「답변을 기다리는 문의에만 초안을 씁니다」 exists to explain.
 *
 * <p>So neither number is a subset of the other and neither may be derived from the other. What this
 * function must not do is hide the difference: the count is the freshness-qualified KPI, and when the KPI
 * excluded channels or could not prove a collection, <b>the cell says so on its own note line</b> rather
 * than printing a smaller number as if it were the whole. `contextStrip` learned this the hard way — it
 * once showed the qualified KPI as 「현재 미답변 문의 0건」 directly above 「들어온 문의 3건」, and both were
 * true under definitions the seller had no way to see.
 *
 * <p>`null` when the read did not land. A failed request says nothing; it does not say zero.
 */
export function unansweredNow(metrics: OperationsMetrics | null | undefined): InflowFact | null {
  if (!metrics) return null;
  const kpi = metrics.kpis.find((k) => k.key === "unansweredInquiries");
  if (!kpi) return null;
  // Unlike `todayInflow` there is no window to check: 미답변 is a present fact, not a day's arrivals.
  // Only the population matters — whether every channel was counted and every collection proven.
  if (kpi.excludedChannels !== 0 || kpi.freshnessUnproven) {
    return { kind: "UNQUALIFIED" };
  }
  return { kind: "COUNT", value: kpi.value };
}

/**
 * <b>Any channel in the population whose latest collection could not be proven.</b>
 *
 * <p>The same signal `/overview` shows and the same one 오늘's old numbers line carried, read from the
 * KPIs rather than computed a second way — two screens deciding 「is the data fresh」 with two rules is
 * how one of them ends up reassuring a seller the other is warning.
 *
 * <p>`muted` at the call site, never `warn`: it qualifies the figures beside it and is read in the same
 * breath as them. The warn colour would spend the page's strongest signal on machinery.
 */
export function freshnessUnproven(metrics: OperationsMetrics | null | undefined): boolean {
  if (!metrics) return false;
  return metrics.kpis.some((kpi) => kpi.freshnessUnproven);
}

export const UNANSWERED_WORD = {
  /** The noun the 문의 screen and `/overview` already use, so one number has one name. */
  lead: "현재 미답변",
  /** Some channel is missing from the population — the figure would be a floor presented as a total. */
  unqualified: "수집 상태 확인 필요",
} as const;

export const RECENT_WORD = {
  lead: "최근 24시간",
  /** Nothing opened in the window — said once instead of three zeros in a row. */
  none: "새로 확인한 일 없음",
  checked: "새로 확인",
  /**
   * 「정리」 is the word `/customer-operations` already uses for this figure, so the two screens name
   * one number one way. 「자동 정리」 said the same thing and added a syllable the product does not use.
   */
  autoResolved: "그중 정리",
  /** 준비 carries what 「(미발송)」 carries on the detail screen: prepared is not sent. */
  draftsPrepared: "초안 준비",
} as const;
