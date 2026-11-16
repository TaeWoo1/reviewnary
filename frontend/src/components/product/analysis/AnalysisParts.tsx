import { useId, useState } from "react";
import { Disclosure } from "../../ui/Disclosure";
import { Link } from "react-router-dom";
import { count } from "../../../lib/format";
import {
  ANALYSIS_PERIODS,
  channelKo,
  channelLine,
  periodLabel,
  type AnalysisAspectRow,
  type AnalysisDays,
  type AnalysisInquiryTopic,
  type AnalysisQuote,
  type AnalysisScope,
  type ProductAnalysisView,
  type SwotCell,
} from "../../../lib/productAnalysis";

/**
 * The pieces of 상품·브랜드 분석 (night unit ②, 2026-11-16), shared by the product page and the store page.
 *
 * <b>What a reader must be able to check.</b> Every number says what it was counted over; strengths and
 * weaknesses are counts with the customer's sentences one press away; opportunities and threats are marked
 * 가설 and name the observation they came from and how to check them. The reading is a word-list rule, said
 * on screen, and the rating decides nothing.
 */

/** 30 · 90 · 180 · 365일 — a choice between views of one thing, so the pressed groove (design §5). */
export function PeriodSwitch({
  days,
  onChange,
}: {
  days: AnalysisDays;
  onChange: (d: AnalysisDays) => void;
}) {
  return (
    <div
      role="group"
      aria-label="분석 기간"
      className="inline-flex gap-0.5 rounded-xl bg-well p-0.5 shadow-[var(--press-groove)]"
    >
      {ANALYSIS_PERIODS.map((d) => (
        <button
          key={d}
          type="button"
          aria-pressed={d === days}
          onClick={() => onChange(d)}
          className={`min-h-[40px] rounded-lg px-3 py-1 text-sm tabular-nums transition-colors sm:min-h-0 focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700 ${
            d === days
              ? "bg-surface font-semibold text-ink shadow-card"
              : "font-medium text-muted hover:text-ink"
          }`}
        >
          {d === 365 ? "1년" : `${d}일`}
        </button>
      ))}
    </div>
  );
}

/** What was read: period, sample, channels, read cap, unlinked rows, empty channels and the method. */
export function ScopeLines({ view }: { view: ProductAnalysisView }) {
  const s: AnalysisScope = view.scope;
  const stars = view.rating.counts;
  return (
    <div className="space-y-1 text-sm text-muted" data-testid="analysis-scope">
      {/* Always in view: the period, the totals, and anything that makes the reading incomplete. */}
      <p className="text-ink">
        <span className="font-semibold">{periodLabel(s.from, s.to)}</span> (
        {s.days}일) · 리뷰 {count(s.reviews)}건 · 문의 {count(s.inquiries)}건
      </p>
      {s.reviewsCapped ? (
        <p className="text-warn">
          리뷰가 많아 최근 {count(s.reviewsRead)}건만 읽었어요. 아래 리뷰 수는
          읽은 {count(s.reviewsRead)}건 기준이에요.
        </p>
      ) : null}
      {s.inquiriesCapped ? (
        <p className="text-warn">
          문의가 많아 최근 {count(s.inquiriesRead)}건만 읽었어요. 아래 문의 수는
          읽은 {count(s.inquiriesRead)}건 기준이에요.
        </p>
      ) : null}
      {s.gaps.map((g) => (
        <p key={g.channelCode} className="text-warn">
          {g.everHadRows
            ? `이 기간 ${channelKo(g.channelCode)}에서 들어온 리뷰·문의가 없어요. 수집이 빠졌을 수 있어요.`
            : `${channelKo(g.channelCode)}은(는) 리뷰·문의가 들어온 적이 없어요. 연결 전이거나 수집되지 않은 채널이에요.`}
        </p>
      ))}
      {/* Folded, not removed: channels, unlinked rows, what was left out of the counts, the rating, the method. */}
      <Disclosure label="자료 범위·분류 방법 보기" summaryClassName="-ml-2">
        <div className="space-y-1 pt-1">
          {s.channels.length > 0 ? (
            <p>채널별: {s.channels.map(channelLine).join("  /  ")}</p>
          ) : null}
          {s.unlinkedReviews + s.unlinkedInquiries > 0 ? (
            <p>
              상품이 연결되지 않은 리뷰 {count(s.unlinkedReviews)}건 · 문의{" "}
              {count(s.unlinkedInquiries)}건은{" "}
              {s.unlinkedIncluded
                ? "가게 전체에 포함했어요."
                : "이 상품 분석에 들어가지 않았어요."}
            </p>
          ) : null}
          {s.unspecifiedReviews + s.unspecifiedInquiries > 0 ? (
            <p>
              「(미지정 상품)」으로 들어온 리뷰 {count(s.unspecifiedReviews)}건
              · 문의 {count(s.unspecifiedInquiries)}건은{" "}
              {s.unlinkedIncluded
                ? "가게 전체에 포함했어요."
                : "이 상품 분석에 들어가지 않았어요."}{" "}
              파일에 상품명이 없던 자료예요.
            </p>
          ) : null}
          {s.inquiriesUnclassified > 0 ? (
            <p>
              주제를 분류하지 못한 문의 {count(s.inquiriesUnclassified)}건은
              주제 집계에서 뺐어요.
            </p>
          ) : null}
          {s.unattributedPraiseReviews > 0 ? (
            <p>
              어느 점이 좋은지 말하지 않은 칭찬 리뷰{" "}
              {count(s.unattributedPraiseReviews)}건은 강점으로 세지 않았어요.
            </p>
          ) : null}
          {view.rating.rated > 0 ? (
            <p>
              별점(참고만):{" "}
              {[5, 4, 3, 2, 1]
                .map((n) => `${n}점 ${count(stars[n - 1] ?? 0)}`)
                .join(" · ")}
              . 별점은 강점·약점 판단에 쓰지 않았어요.
            </p>
          ) : null}
          <p>
            읽는 방법: 고객 문장의 단어 규칙 (모델 분석 아님 · 정확도 측정 전).
            경쟁사·시장 자료는 없어 비교하지 않았어요.
          </p>
        </div>
      </Disclosure>
    </div>
  );
}

/** Where an observation's evidence lives on the page: an aspect row or an inquiry-topic row. */
type Target = { kind: "aspect" | "topic"; name: string };

function targetOf(key: string): Target | null {
  if (key.startsWith("W:inquiry:"))
    return { kind: "topic", name: key.slice("W:inquiry:".length) };
  if (key.startsWith("S:") || key.startsWith("W:"))
    return { kind: "aspect", name: key.slice(2) };
  return null;
}

const QUADRANTS: {
  key: keyof Omit<ProductAnalysisView["swot"], "insufficient">;
  title: string;
  kind: string;
}[] = [
  { key: "strengths", title: "강점", kind: "관찰" },
  { key: "weaknesses", title: "약점", kind: "관찰" },
  { key: "opportunities", title: "기회", kind: "가설" },
  { key: "threats", title: "위협", kind: "가설" },
];

/** 강점 · 약점 are counted; 기회 · 위협 are hypotheses that name the observation they came from. */
const SWOT_SHOWN = 4;

/**
 * The cells shown before 「더 보기」: when a quadrant holds both review and inquiry observations, half of each, so
 * an inquiry finding is never folded away merely because review findings were listed first. Order is kept.
 */
function firstCells(all: SwotCell[]): SwotCell[] {
  if (all.length <= SWOT_SHOWN) return all;
  const half = SWOT_SHOWN / 2;
  const reviews = all.filter((c) => c.source === "REVIEW");
  const inquiries = all.filter((c) => c.source === "INQUIRY");
  const take = new Set<SwotCell>([...reviews.slice(0, half), ...inquiries.slice(0, half)]);
  for (const c of all) {
    if (take.size >= SWOT_SHOWN) break;
    take.add(c);
  }
  return all.filter((c) => take.has(c));
}

export function SwotGrid({
  view,
  onJump,
}: {
  view: ProductAnalysisView;
  onJump?: (key: string) => void;
}) {
  const swot = view.swot;
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const titles = new Map<string, string>();
  [...swot.strengths, ...swot.weaknesses].forEach((c) =>
    titles.set(c.key, c.title),
  );
  return (
    <div className="space-y-2">
      {swot.insufficient ? (
        <p className="text-sm text-warn">{swot.insufficient}</p>
      ) : null}
      <div className="grid grid-cols-1 gap-px overflow-hidden rounded-xl border border-line bg-line sm:grid-cols-2">
        {QUADRANTS.map((q) => {
          const all: SwotCell[] = swot[q.key];
          const isOpen = expanded.has(q.key);
          const cells = isOpen ? all : firstCells(all);
          const more = all.length - cells.length;
          return (
            <section
              key={q.key}
              aria-label={`${q.title} (${q.kind})`}
              className="min-w-0 bg-surface p-4"
            >
              <h3 className="text-base font-semibold text-ink">
                {q.title}{" "}
                <span className="text-sm font-normal text-muted">{q.kind}</span>
              </h3>
              {all.length === 0 ? (
                <p className="mt-2 text-sm text-muted">
                  근거가 충분한 항목이 없어요.
                </p>
              ) : (
                <ul className="mt-2 space-y-3">
                  {cells.map((c) => (
                    <li key={c.key} className="min-w-0">
                      <p className="font-medium text-ink [overflow-wrap:anywhere]">
                        {c.kind === "OBSERVED" && onJump && targetOf(c.key) ? (
                          <button
                            type="button"
                            onClick={() => onJump(c.key)}
                            className="inline-flex min-h-[44px] items-center rounded text-left underline decoration-line underline-offset-4 hover:decoration-ink focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700 sm:min-h-0"
                            aria-label={`${c.title}: 근거 문장 열기`}
                          >
                            {c.title}
                          </button>
                        ) : (
                          c.title
                        )}{" "}
                        <span className="text-xs font-normal text-muted">
                          {c.source === "INQUIRY" ? "문의" : "리뷰"}
                        </span>
                      </p>
                      <p className="text-sm text-muted">{c.basis}</p>
                      {c.kind === "HYPOTHESIS" ? (
                        <>
                          {c.derivedFrom.length > 0 ? (
                            <p className="text-sm text-muted">
                              근거 관찰:{" "}
                              {c.derivedFrom.map((k, i) => (
                                <span key={k}>
                                  {i > 0 ? ", " : null}
                                  {onJump && targetOf(k) ? (
                                    <button
                                      type="button"
                                      onClick={() => onJump(k)}
                                      className="inline-flex min-h-[44px] items-center rounded underline underline-offset-2 focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700 sm:min-h-0"
                                    >
                                      {titles.get(k) ?? k}
                                    </button>
                                  ) : (
                                    (titles.get(k) ?? k)
                                  )}
                                </span>
                              ))}
                            </p>
                          ) : null}
                          {c.check ? (
                            <p className="text-sm text-muted">
                              확인 방법: {c.check}
                            </p>
                          ) : null}
                        </>
                      ) : null}
                    </li>
                  ))}
                </ul>
              )}
              {more > 0 ? (
                <button
                  type="button"
                  onClick={() =>
                    setExpanded((prev) => new Set(prev).add(q.key))
                  }
                  className="mt-2 inline-flex min-h-[44px] items-center rounded text-sm text-ink underline underline-offset-2 focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700 sm:min-h-0"
                >
                  {q.title} {more}개 더 보기
                </button>
              ) : null}
            </section>
          );
        })}
      </div>
    </div>
  );
}

/**
 * Per aspect: reviews complaining (left, `bad`) and praising (right, ink), on ONE scale in reviews, the
 * numbers printed beside each bar. A row opens the sentences it counted — the drill-down exists, so the
 * press is offered (design §8-J).
 */
export function AspectChart({
  aspects,
  reviews,
  open: openProp,
  onToggle,
  idPrefix = "",
}: {
  aspects: AnalysisAspectRow[];
  reviews: number;
  open?: string | null;
  onToggle?: (aspect: string | null) => void;
  idPrefix?: string;
}) {
  const [own, setOwn] = useState<string | null>(null);
  const open = openProp === undefined ? own : openProp;
  const setOpen = onToggle ?? setOwn;
  if (aspects.length === 0) {
    return (
      <p className="text-sm text-muted">
        이 기간 리뷰에서 특정한 점을 칭찬하거나 불만을 말한 문장을 찾지
        못했어요.
      </p>
    );
  }
  const max = Math.max(
    1,
    ...aspects.map((a) => Math.max(a.praiseReviews, a.complaintReviews)),
  );
  return (
    <div>
      <p className="mb-2 text-xs text-muted">
        읽은 리뷰 {count(reviews)}건 가운데 그 점을 말한 리뷰 수 · 한 리뷰는
        칭찬·불만 각각 한 번만 셈 · 가장 긴 막대 {count(max)}건
      </p>
      <div className="mb-1 grid grid-cols-[4.5rem_minmax(0,1fr)_minmax(0,1fr)] gap-x-2 text-xs text-muted">
        <span />
        <span className="text-right">불만</span>
        <span>칭찬</span>
      </div>
      <ul className="divide-y divide-line/70">
        {aspects.map((a) => {
          const isOpen = open === a.aspect;
          return (
            <li key={a.aspect}>
              <button
                type="button"
                id={`${idPrefix}aspect-${a.aspect}`}
                aria-expanded={isOpen}
                onClick={() => setOpen(isOpen ? null : a.aspect)}
                className="grid min-h-[44px] w-full grid-cols-[4.5rem_minmax(0,1fr)_minmax(0,1fr)] items-center gap-x-2 rounded py-2 text-left hover:bg-canvas focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700"
                aria-label={`${a.aspect}: 불만 ${a.complaintReviews}건, 칭찬 ${a.praiseReviews}건. 눌러서 문장 보기`}
              >
                <span className="text-sm font-medium text-ink">{a.aspect}</span>
                <span className="flex items-center justify-end gap-1.5">
                  <span className="text-xs tabular-nums text-muted">
                    {a.complaintReviews > 0 ? a.complaintReviews : ""}
                  </span>
                  <span
                    className="h-3 rounded-l bg-bad/70"
                    style={{ width: `${(a.complaintReviews / max) * 100}%` }}
                    aria-hidden="true"
                  />
                </span>
                <span className="flex items-center gap-1.5">
                  <span
                    className="h-3 rounded-r bg-ink/70"
                    style={{ width: `${(a.praiseReviews / max) * 100}%` }}
                    aria-hidden="true"
                  />
                  <span className="text-xs tabular-nums text-muted">
                    {a.praiseReviews > 0 ? a.praiseReviews : ""}
                  </span>
                </span>
              </button>
              {isOpen ? <AspectQuotes row={a} /> : null}
            </li>
          );
        })}
      </ul>
      <table className="sr-only">
        <caption>속성별 불만·칭찬 리뷰 수 (읽은 리뷰 {reviews}건 기준)</caption>
        <thead>
          <tr>
            <th>속성</th>
            <th>불만</th>
            <th>칭찬</th>
          </tr>
        </thead>
        <tbody>
          {aspects.map((a) => (
            <tr key={a.aspect}>
              <td>{a.aspect}</td>
              <td>{a.complaintReviews}</td>
              <td>{a.praiseReviews}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function AspectQuotes({ row }: { row: AnalysisAspectRow }) {
  return (
    <div className="grid grid-cols-1 gap-3 pb-3 pl-2 sm:grid-cols-2">
      <QuoteList
        title={`불만 문장 ${row.complaintReviews}건 중`}
        quotes={row.complaintQuotes}
      />
      <QuoteList
        title={`칭찬 문장 ${row.praiseReviews}건 중`}
        quotes={row.praiseQuotes}
      />
      {row.issueId ? (
        <Link
          to={`/memory/${row.issueId}`}
          className="inline-flex min-h-[44px] items-center text-sm text-ink underline underline-offset-2 focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700 sm:col-span-2 sm:min-h-0"
        >
          반복 문제 화면에서 {row.aspect} 문제 전체 보기
        </Link>
      ) : null}
    </div>
  );
}

function QuoteList({
  title,
  quotes,
}: {
  title: string;
  quotes: AnalysisQuote[];
}) {
  if (quotes.length === 0) return null;
  return (
    <div className="min-w-0">
      <p className="text-xs text-muted">
        {title} 최근 {quotes.length}건
      </p>
      <ul className="mt-1 space-y-1.5">
        {quotes.map((q) => (
          <li key={`${q.reviewId}:${q.text}`}>
            <Link
              to={`/reviews/reply/${q.reviewId}`}
              className="flex min-h-[44px] items-center rounded text-sm text-ink hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700 [overflow-wrap:anywhere] sm:min-h-0"
            >
              「{q.text}」
            </Link>
            <span className="text-xs text-muted">
              {q.rating ? `${q.rating}점` : "별점 없음"} ·{" "}
              {channelKo(q.channelCode)} · {q.on}
              {q.problem ? ` · ${q.problem}` : ""}
            </span>
          </li>
        ))}
      </ul>
    </div>
  );
}

/** Inquiry topics over the INQUIRY denominator — a separate list, never merged with the review bars. */
export function InquiryTopics({
  topics,
  inquiries,
  open: openProp,
  onToggle,
  idPrefix = "",
}: {
  topics: AnalysisInquiryTopic[];
  inquiries: number;
  open?: string | null;
  onToggle?: (topic: string | null) => void;
  idPrefix?: string;
}) {
  const [own, setOwn] = useState<string | null>(null);
  const open = openProp === undefined ? own : openProp;
  const setOpen = onToggle ?? setOwn;
  if (topics.length === 0) {
    return (
      <p className="text-sm text-muted">이 기간 주제가 분류된 문의가 없어요.</p>
    );
  }
  const max = Math.max(1, ...topics.map((t) => t.inquiries));
  return (
    <div>
      <p className="mb-2 text-xs text-muted">
        읽은 문의 {count(inquiries)}건 가운데 주제별 문의 수 · 리뷰와 다른
        분모예요
      </p>
      <ul className="divide-y divide-line/70">
        {topics.map((t) => {
          const isOpen = open === t.topic;
          return (
            <li key={t.topic}>
              <button
                type="button"
                id={`${idPrefix}topic-${t.topic}`}
                aria-expanded={isOpen}
                onClick={() => setOpen(isOpen ? null : t.topic)}
                className="grid min-h-[44px] w-full grid-cols-[4.5rem_minmax(0,1fr)_3.5rem] items-center gap-x-2 rounded py-2 text-left hover:bg-canvas focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700"
                aria-label={`${t.topic} 문의 ${t.inquiries}건. 눌러서 예시 보기`}
              >
                <span className="text-sm font-medium text-ink">{t.topic}</span>
                <span
                  className="h-3 rounded bg-muted/40"
                  style={{ width: `${(t.inquiries / max) * 100}%` }}
                  aria-hidden="true"
                />
                <span className="text-right text-xs tabular-nums text-muted">
                  {t.inquiries}건
                </span>
              </button>
              {isOpen ? (
                <ul className="space-y-1 pb-3 pl-2">
                  {t.examples.map((e) => (
                    <li key={e.inquiryId}>
                      <Link
                        to={`/inquiries/${e.inquiryId}`}
                        className="inline-flex min-h-[44px] items-center rounded text-sm text-ink hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700 [overflow-wrap:anywhere] sm:min-h-0"
                      >
                        「{e.text}」
                      </Link>
                      <span className="text-xs text-muted">
                        {" "}
                        · {channelKo(e.channelCode)}
                        {e.on ? ` · ${e.on}` : ""}
                      </span>
                    </li>
                  ))}
                </ul>
              ) : null}
            </li>
          );
        })}
      </ul>
    </div>
  );
}

/**
 * The whole analysis body for one view — scope, SWOT, aspects, inquiry topics. An observation's title opens the
 * row its count came from (and the sentences under it) and brings it into view, so the evidence is one press away.
 */
export function AnalysisBody({ view }: { view: ProductAnalysisView }) {
  const prefix = useId().replace(/:/g, "");
  const [openAspect, setOpenAspect] = useState<string | null>(null);
  const [openTopic, setOpenTopic] = useState<string | null>(null);
  const jump = (key: string) => {
    const t = targetOf(key);
    if (!t) return;
    if (t.kind === "aspect") setOpenAspect(t.name);
    else setOpenTopic(t.name);
    const id = `${prefix}${t.kind}-${t.name}`;
    requestAnimationFrame(() => {
      const el = document.getElementById(id);
      if (el) {
        el.scrollIntoView?.({ block: "start", behavior: "smooth" });
        el.focus({ preventScroll: true });
      }
    });
  };
  return (
    <div className="space-y-6">
      <ScopeLines view={view} />
      <SwotGrid view={view} onJump={jump} />
      <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
        <section
          aria-label="속성별 칭찬과 불만"
          className="min-w-0 scroll-mt-20"
        >
          <h3 className="mb-2 text-base font-semibold text-ink">
            속성별 칭찬과 불만
          </h3>
          <AspectChart
            aspects={view.aspects}
            reviews={view.scope.reviewsRead}
            open={openAspect}
            onToggle={setOpenAspect}
            idPrefix={prefix}
          />
        </section>
        <section aria-label="반복되는 문의 주제" className="min-w-0">
          <h3 className="mb-2 text-base font-semibold text-ink">
            반복되는 문의 주제
          </h3>
          <InquiryTopics
            topics={view.inquiryTopics}
            inquiries={view.scope.inquiriesRead}
            open={openTopic}
            onToggle={setOpenTopic}
            idPrefix={prefix}
          />
        </section>
      </div>
    </div>
  );
}
