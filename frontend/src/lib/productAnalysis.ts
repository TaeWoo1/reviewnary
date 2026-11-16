/**
 * 상품·브랜드 분석 (night unit ②, 2026-11-16) — the wire shape of `/api/products/{id}/analysis`,
 * `/api/products/analysis/store` and `/api/products/analysis`, and the few words the screens use for it.
 *
 * Every count travels with its denominator, and review counts and inquiry counts never share one. The reading
 * is rule-based (word lists), not a model — the screens say so.
 */

export type AnalysisDays = 30 | 90 | 180 | 365;
export const ANALYSIS_PERIODS: AnalysisDays[] = [30, 90, 180, 365];
export const DEFAULT_ANALYSIS_DAYS: AnalysisDays = 90;

/** The whole period's rows from one channel, and how many this reading read (they differ when the read was capped). */
export interface AnalysisChannelCount {
  channelCode: string;
  reviews: number;
  inquiries: number;
  reviewsRead: number;
  inquiriesRead: number;
}

/** 「네이버 리뷰 30 · 문의 5」, plus 「(이번에 읽은 것 리뷰 0 · 문의 0)」 when the capped read saw fewer. */
export function channelLine(c: AnalysisChannelCount): string {
  const base = `${channelKo(c.channelCode)} 리뷰 ${c.reviews.toLocaleString("ko-KR")} · 문의 ${c.inquiries.toLocaleString("ko-KR")}`;
  if (c.reviewsRead === c.reviews && c.inquiriesRead === c.inquiries) return base;
  return `${base} (이번에 읽은 것 리뷰 ${c.reviewsRead.toLocaleString("ko-KR")} · 문의 ${c.inquiriesRead.toLocaleString("ko-KR")})`;
}

export interface AnalysisChannelGap {
  channelCode: string;
  everHadRows: boolean;
}

export interface AnalysisScope {
  range: "PRODUCT" | "STORE";
  from: string;
  to: string;
  days: number;
  readCap: number;
  reviews: number;
  reviewsRead: number;
  reviewsCapped: boolean;
  inquiries: number;
  inquiriesRead: number;
  inquiriesCapped: boolean;
  inquiriesUnclassified: number;
  /** No product id at all. */
  unlinkedReviews: number;
  unlinkedInquiries: number;
  /** Filed by ingest under the (미지정 상품) bucket — a product id, no real product. A different gap; never summed. */
  unspecifiedReviews: number;
  unspecifiedInquiries: number;
  unlinkedIncluded: boolean;
  channels: AnalysisChannelCount[];
  gaps: AnalysisChannelGap[];
  unattributedPraiseReviews: number;
  minReviews: number;
  enoughReviews: boolean;
}

export interface AnalysisMethod {
  /** RULE_BASED · AI_READ (every review read by the review-aspect model) · MIXED (some, the rest by word rules). */
  kind: string;
  complaintRules: string;
  praiseRules: string;
  inquiryRules: string;
  aiReader?: string | null;
  aiReadReviews?: number;
  reviewsRead?: number;
}

/** What one 「AI로 읽기」 press did (backend `ProductAspectReadingService.ReadSummary`). */
export interface AspectReadSummary {
  calls: number;
  textsRead: number;
  remaining: number;
  failedBatches: number;
  /** QUOTA — today's AI ceiling or the evaluation budget; CALLS — this press's bound. */
  stopped: "QUOTA" | "CALLS" | null;
  outlookWritten: boolean;
  analysis: ProductAnalysisView;
}

export interface AnalysisQuote {
  reviewId: string;
  text: string;
  rating: number | null;
  channelCode: string;
  on: string;
  problem: string | null;
}

export interface AnalysisAspectRow {
  aspect: string;
  praiseReviews: number;
  complaintReviews: number;
  problems: string[];
  issueId: string | null;
  recentComplaints: number;
  earlierComplaints: number;
  praiseQuotes: AnalysisQuote[];
  complaintQuotes: AnalysisQuote[];
}

export interface AnalysisInquiryRef {
  inquiryId: string;
  text: string;
  channelCode: string;
  on: string | null;
}

export interface AnalysisInquiryTopic {
  topic: string;
  inquiries: number;
  examples: AnalysisInquiryRef[];
}

export interface SwotCell {
  key: string;
  kind: "OBSERVED" | "HYPOTHESIS";
  /** AI: an opportunity/threat the review-aspect model wrote from the observed cells it names. */
  source: "REVIEW" | "INQUIRY" | "AI";
  title: string;
  basis: string;
  count: number;
  denominator: number;
  derivedFrom: string[];
  check: string | null;
}

export interface Swot {
  strengths: SwotCell[];
  weaknesses: SwotCell[];
  opportunities: SwotCell[];
  threats: SwotCell[];
  insufficient: string | null;
}

export interface ProductAnalysisView {
  productId: string | null;
  productName: string | null;
  scope: AnalysisScope;
  method: AnalysisMethod;
  rating: { rated: number; counts: number[] };
  aspects: AnalysisAspectRow[];
  inquiryTopics: AnalysisInquiryTopic[];
  swot: Swot;
}

export interface ProductPoint {
  productId: string;
  name: string;
  reviews: number;
  praiseReviews: number;
  complaintReviews: number;
  inquiries: number;
  enough: boolean;
}

export interface ProductComparison {
  from: string;
  to: string;
  days: number;
  reviewsRead: number;
  reviewsCapped: boolean;
  unlinkedReviews: number;
  unlinkedInquiries: number;
  unspecifiedReviews: number;
  unspecifiedInquiries: number;
  minReviews: number;
  method: AnalysisMethod;
  products: ProductPoint[];
}

const CHANNEL_KO: Record<string, string> = { NAVER: "네이버", COUPANG: "쿠팡", CAFE24: "카페24" };

export function channelKo(code: string): string {
  return CHANNEL_KO[code] ?? "기타 경로";
}

/** 「42%」 — a share of a stated whole; never computed over zero. */
export function percent(part: number, whole: number): string {
  if (whole <= 0) return "—";
  return `${Math.round((part / whole) * 100)}%`;
}

/** 「8월 19일 ~ 11월 16일」 */
export function periodLabel(from: string, to: string): string {
  const d = (iso: string) => {
    const [, m, day] = iso.split("-");
    return `${Number(m)}월 ${Number(day)}일`;
  };
  return `${d(from)} ~ ${d(to)}`;
}

/** The period from the URL, or the default — never a value the server would refuse. */
export function daysFrom(raw: string | null): AnalysisDays {
  const n = Number(raw);
  return (ANALYSIS_PERIODS as number[]).includes(n) ? (n as AnalysisDays) : DEFAULT_ANALYSIS_DAYS;
}
