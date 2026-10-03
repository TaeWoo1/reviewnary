import type {
  IssueEvidenceView,
  IssueKnowledgeOnHand,
  IssueProductEvidenceView,
  IssueRatingDistributionView,
} from "./types";

/**
 * The sentences the Repeated Issue workspace is allowed to say about counts it did not compute.
 *
 * Every function here turns numbers a read returned into Korean. None of them derives a third number
 * from two, and that is the rule the module exists to hold: the workspace's failure mode is not a
 * missing feature, it is a plausible figure nobody measured.
 */

/**
 * 「리뷰 1,761건 중 16건」 — the pair, never a percentage.
 *
 * <b>A rate would be a different claim than the one the data supports.</b> `productReviews` counts
 * every review of the product; `evidenceCount` counts the ones that named this problem. The
 * extractor only reads a review that has a body, so a review it never read is in the denominator and
 * cannot be in the numerator — dividing them would assert a rate over an examined population this
 * product has not measured. The pair says what is true: this many said it, out of this many reviews.
 */
export function repeatLine(row: IssueProductEvidenceView): string {
  return `리뷰 ${row.productReviews.toLocaleString("ko-KR")}건 중 ${row.evidenceCount.toLocaleString("ko-KR")}건이 이 문제를 말했습니다`;
}

/**
 * The span of THIS product's evidence, or null when it has none to state.
 *
 * The issue's own first/last dates are deliberately not borrowed here — they are the union over every
 * product, and lending them to a row would let another product's recent review make this one look
 * current.
 */
export function productSpanLine(row: IssueProductEvidenceView): string | null {
  if (!row.firstOccurredOn || !row.lastOccurredOn) return null;
  if (row.firstOccurredOn === row.lastOccurredOn) return row.firstOccurredOn;
  return `${row.firstOccurredOn} ~ ${row.lastOccurredOn}`;
}

/**
 * Evidence whose review resolved to no product, or null when there is none.
 *
 * It is stated rather than hidden: those rows are in the issue's total and in no product's row, so a
 * seller adding the product rows up would otherwise find a number that does not reach the total and
 * have no way to learn why.
 */
export function unattributedLine(unattributed: number): string | null {
  if (unattributed <= 0) return null;
  return `어느 상품인지 확인되지 않은 근거가 ${unattributed.toLocaleString("ko-KR")}건 있습니다. 위 상품별 합계에는 들어 있지 않습니다.`;
}

/**
 * What the company's library says about this problem — one sentence, chosen by what is actually true.
 *
 * <b>Four states, and three of them are not "없습니다".</b> A library that holds nothing, a library
 * that holds things none of which name this problem, and a library that answers it are three
 * different situations with three different next steps; collapsing them into one sentence is how a
 * seller gets told to register knowledge they already registered.
 */
export function knowledgeLine(knowledge: IssueKnowledgeOnHand): string {
  const sources = knowledge.productSources + knowledge.orgSources;
  const mentions = knowledge.productMentions + knowledge.orgMentions;
  if (sources === 0) {
    return "이 문제에 대해 등록된 안내가 아직 없습니다.";
  }
  if (mentions === 0) {
    return `등록된 안내 ${sources}건 가운데 이 문제를 다루는 내용은 찾지 못했습니다.`;
  }
  return `등록된 안내 ${sources}건 가운데 ${mentions}건이 이 문제를 다룹니다.`;
}

/**
 * Which library was read, or null when there was no product library to read.
 *
 * An issue has no product of its own — it is org-wide by construction — so the product lane reads the
 * issue's dominant product. Naming it matters: a seller whose problem spans three products should be
 * able to see that the shelf we looked at was one of them.
 */
export function knowledgeScopeLine(knowledge: IssueKnowledgeOnHand): string | null {
  if (!knowledge.productId || !knowledge.productName) return null;
  return `${knowledge.productName}의 상품 지식과 회사 운영 기준을 확인했습니다.`;
}

/**
 * What the seller is invited to do about a gap — null when there is no gap to act on.
 *
 * Offered only when something is missing. A library that already answers this problem needs no
 * prompt, and printing one anyway is the screen asking for work that is already done.
 */
export function knowledgeGapAction(knowledge: IssueKnowledgeOnHand): string | null {
  const mentions = knowledge.productMentions + knowledge.orgMentions;
  return mentions === 0 ? "답변 기준 채우기" : null;
}

/** One bar of the rating breakdown: a star band and how many pieces of evidence carried it. */
export interface RatingBand {
  key: string;
  labelKo: string;
  count: number;
}

/**
 * How the evidence behind this problem spreads across star ratings — **counts, and nothing else**.
 *
 * <b>No percentage, no average, no "importance".</b> An average star over evidence units is a number
 * about a sample nobody drew: the units here are the ones that named this problem, so their mean
 * says nothing about the product and everything about which sentences the extractor matched. A share
 * would be a rate over that same sample. And ranking a problem by its stars would be an importance
 * judgement no measurement in this repository supports — severity already comes from the problem
 * vocabulary, deliberately never from a rating.
 *
 * <b>It counts 근거, not 리뷰.</b> The grain of evidence is `(review, unit_ordinal)`, so one review
 * that says the same thing twice contributes twice, and the six buckets sum to the issue's evidence
 * total rather than to a number of reviews. The label says 근거 for that reason.
 *
 * <b>Empty bands are kept.</b> A missing 1★ row and a 1★ row reading 0 are the same fact, but only
 * the second one lets a seller see that the problem is being raised entirely inside good ratings —
 * which is exactly what 접착 부족 does on this org (1–2★: 0, 5★: 10).
 */
export function ratingBands(distribution: IssueRatingDistributionView): RatingBand[] {
  return [
    { key: "5", labelKo: "5점", count: distribution.rating5 },
    { key: "4", labelKo: "4점", count: distribution.rating4 },
    { key: "3", labelKo: "3점", count: distribution.rating3 },
    { key: "2", labelKo: "2점", count: distribution.rating2 },
    { key: "1", labelKo: "1점", count: distribution.rating1 },
    { key: "unrated", labelKo: "별점 없음", count: distribution.unrated },
  ];
}

/**
 * Whether the breakdown is worth drawing at all — false when there is nothing to spread.
 *
 * A table of six zeroes is not a finding; it is a read that has no evidence yet, and the section
 * simply does not render.
 */
export function hasRatingEvidence(distribution: IssueRatingDistributionView): boolean {
  return ratingBands(distribution).some((band) => band.count > 0);
}

/** One month of the evidence trend: the bucket and how many pieces of evidence fell in it. */
export interface EvidenceMonth {
  /** `YYYY-MM`. */
  key: string;
  count: number;
}

/** How many months the trend may show at once. */
const TREND_WINDOW_MONTHS = 24;

function monthKey(isoDate: string): string {
  return isoDate.slice(0, 7);
}

function shiftMonth(key: string, by: number): string {
  const year = Number(key.slice(0, 4));
  const month = Number(key.slice(5, 7)) - 1 + by;
  const y = year + Math.floor(month / 12);
  const m = ((month % 12) + 12) % 12;
  return `${String(y).padStart(4, "0")}-${String(m + 1).padStart(2, "0")}`;
}

/**
 * <b>When this problem was said, month by month</b> — the one question a date pair cannot answer.
 *
 * <b>It counts, and it counts nothing new.</b> Each bucket is the number of evidence rows whose
 * `occurredOn` falls in that month, from the rows the detail read already returned. There is no second
 * read, no rate, no trend verdict: whether this problem is 급증 or 집중 is the server's judgement
 * (`IssueChangeView`), and a chart that implied one would be a second opinion with no rule behind it.
 *
 * <b>The axis runs to `throughMonth`, which is today.</b> Stopping at the last piece of evidence would
 * draw a chart that ends where the problem stopped being mentioned, which reads as 「still happening」
 * for a problem last seen a year ago. The empty months after the final bar are the honest part.
 *
 * <b>Bounded at {@link TREND_WINDOW_MONTHS}.</b> This org holds a problem whose first evidence is from
 * 2015; a bar per month since would be 137 bars three pixels wide. The window starts at the first
 * evidence month or 24 months back, whichever is later — so a short-lived problem is drawn whole and a
 * decade-old one is drawn recent. What falls outside is not hidden: see {@link evidenceBeforeTrend}.
 */
export function evidenceMonths(
  evidence: readonly IssueEvidenceView[],
  throughMonth: string,
): EvidenceMonth[] {
  if (evidence.length === 0) return [];
  const months = evidence.map((row) => monthKey(row.occurredOn));
  const earliest = months.reduce((a, b) => (a < b ? a : b));
  const latest = months.reduce((a, b) => (a > b ? a : b));
  const end = latest > throughMonth ? latest : throughMonth;
  const floor = shiftMonth(end, -(TREND_WINDOW_MONTHS - 1));
  const start = earliest > floor ? earliest : floor;
  const counted = new Map<string, number>();
  for (const key of months) {
    if (key >= start) counted.set(key, (counted.get(key) ?? 0) + 1);
  }
  const out: EvidenceMonth[] = [];
  for (let key = start; key <= end; key = shiftMonth(key, 1)) {
    out.push({ key, count: counted.get(key) ?? 0 });
  }
  return out;
}

/**
 * Evidence older than the drawn window, or 0 when the window holds all of it.
 *
 * Stated rather than dropped: the bars are a count of this problem's evidence, and a seller adding them
 * up must be able to reach the issue's own total or learn why they cannot.
 */
export function evidenceBeforeTrend(
  evidence: readonly IssueEvidenceView[],
  months: readonly EvidenceMonth[],
): number {
  if (months.length === 0) return 0;
  const start = months[0].key;
  return evidence.filter((row) => monthKey(row.occurredOn) < start).length;
}

/** 「가장 많았던 달 4건」, or null when every month is empty. */
export function trendPeakLine(months: readonly EvidenceMonth[]): string | null {
  const peak = months.reduce((max, m) => (m.count > max ? m.count : max), 0);
  return peak > 0 ? `가장 많았던 달 ${peak.toLocaleString("ko-KR")}건` : null;
}
