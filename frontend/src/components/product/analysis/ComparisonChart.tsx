import { useEffect, useId, useRef, useState, type KeyboardEvent } from "react";
import { Link, useNavigate } from "react-router-dom";
import { count } from "../../../lib/format";
import {
  percent,
  periodLabel,
  type ProductComparison,
  type ProductPoint,
} from "../../../lib/productAnalysis";

/**
 * 가게 안 상품 비교 — the store's own products on two axes that share ONE denominator (the product's reviews in
 * the period): x = reviews praising a named aspect, y = reviews naming a problem, both in %. No competitor and
 * no market position is drawn: there is no such data. Inquiries have their own denominator, so they sit in the
 * table, never on the plot. Products under the minimum sample are listed, not plotted.
 *
 * Hover and keyboard show the exact counts (design §8-J); a point opens its product, which the backend serves.
 */
export function ComparisonChart({
  data,
  currentId,
}: {
  data: ProductComparison;
  currentId?: string;
}) {
  const navigate = useNavigate();
  const liveId = useId();
  // The server sends every product with activity in the period, most-reviewed first. The plot draws at most
  // PLOT_MAX of those with enough reviews (always including this product); the rest stay in the table.
  const enough = data.products.filter((p) => p.enough);
  const plotted = enough.slice(0, PLOT_MAX);
  const current = enough.find((p) => p.productId === currentId);
  if (current && !plotted.includes(current)) plotted.push(current);
  const offPlot = enough.length - plotted.length;
  const thin = data.products.filter((p) => !p.enough);
  const [active, setActive] = useState<number | null>(null);

  // The viewBox is the container's real width, so one SVG unit is one CSS pixel and the 11–12px labels stay
  // 11–12px on a phone (a fixed 640 viewBox shrank them to ~6px at 360). Height follows, within bounds.
  const box = useRef<HTMLDivElement>(null);
  const [W, setW] = useState(640);
  useEffect(() => {
    const el = box.current;
    if (!el) return;
    const measure = () =>
      setW(Math.max(280, Math.round(el.getBoundingClientRect().width) || 640));
    measure();
    if (typeof ResizeObserver === "undefined") return;
    const ro = new ResizeObserver(measure);
    ro.observe(el);
    return () => ro.disconnect();
  }, []);
  const H = Math.round(Math.min(320, Math.max(240, W * 0.6)));
  const pad = { l: 40, r: 16, t: 20, b: 36 };
  const x = (p: ProductPoint) =>
    pad.l + (p.praiseReviews / p.reviews) * (W - pad.l - pad.r);
  const y = (p: ProductPoint) =>
    H - pad.b - (p.complaintReviews / p.reviews) * (H - pad.t - pad.b);
  const shown = active == null ? null : plotted[active];
  const placedLabels = new Map<
    string,
    { x: number; y: number; anchor: "start" | "end" }
  >();

  const onKey = (e: KeyboardEvent<SVGSVGElement>) => {
    if (plotted.length === 0) return;
    if (e.key === "ArrowRight" || e.key === "ArrowDown") {
      e.preventDefault();
      setActive((i) => (i == null ? 0 : Math.min(plotted.length - 1, i + 1)));
    } else if (e.key === "ArrowLeft" || e.key === "ArrowUp") {
      e.preventDefault();
      setActive((i) => (i == null ? 0 : Math.max(0, i - 1)));
    } else if (e.key === "Enter" && shown) {
      navigate(`/products/${shown.productId}`);
    }
  };

  return (
    <div className="space-y-3">
      <p className="text-xs text-muted">
        {periodLabel(data.from, data.to)} ({data.days}일) · 가로: 칭찬한 리뷰
        비율 · 세로: 불만을 말한 리뷰 비율 · 둘 다 그 상품 리뷰 수가 분모 · 리뷰{" "}
        {data.minReviews}건 이상인 상품만 · 같은 규칙으로 읽음
      </p>
      {data.reviewsCapped ? (
        <p className="text-xs text-warn">
          리뷰가 많아 최근 {count(data.reviewsRead)}건만 읽었어요.
        </p>
      ) : null}
      {plotted.length === 0 ? (
        <p className="text-sm text-muted">
          이 기간 리뷰가 {data.minReviews}건 이상인 상품이 없어 그래프를 그리지
          않았어요.
        </p>
      ) : (
        <div className="relative" ref={box}>
          <svg
            viewBox={`0 0 ${W} ${H}`}
            className="h-auto w-full rounded focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700"
            role="img"
            aria-label="가게 안 상품 비교: 칭찬 비율과 불만 비율. 화살표로 상품을 고르고 Enter로 열어요"
            aria-describedby={liveId}
            tabIndex={0}
            onKeyDown={onKey}
            onMouseLeave={() => setActive(null)}
          >
            {[0, 25, 50, 75, 100].map((t) => {
              const gx = pad.l + (t / 100) * (W - pad.l - pad.r);
              const gy = H - pad.b - (t / 100) * (H - pad.t - pad.b);
              return (
                <g key={t}>
                  <line
                    x1={pad.l}
                    x2={W - pad.r}
                    y1={gy}
                    y2={gy}
                    className="stroke-line"
                    strokeWidth={1}
                  />
                  <line
                    x1={gx}
                    x2={gx}
                    y1={pad.t}
                    y2={H - pad.b}
                    className="stroke-line"
                    strokeWidth={1}
                  />
                  <text
                    x={pad.l - 6}
                    y={gy + 4}
                    textAnchor="end"
                    className="fill-muted text-[11px]"
                  >
                    {t}%
                  </text>
                  <text
                    x={gx}
                    y={H - pad.b + 16}
                    textAnchor="middle"
                    className="fill-muted text-[11px]"
                  >
                    {t}%
                  </text>
                </g>
              );
            })}
            <text
              x={W - pad.r}
              y={H - 4}
              textAnchor="end"
              className="fill-muted text-[11px]"
            >
              칭찬한 리뷰 비율 →
            </text>
            <text
              x={pad.l + 4}
              y={pad.t - 6}
              className="fill-muted text-[11px]"
            >
              ↑ 불만 비율
            </text>
            {(() => {
              // Label layout: each label beside its point, flipped inward near the right/top edge, and pushed down
              // a line when it would sit on a label already placed (two products with similar rates).
              placedLabels.clear();
              const boxes: { x0: number; x1: number; y: number }[] = [];
              const wanted = plotted.filter(
                (p, i) =>
                  p.productId === currentId ||
                  active === i ||
                  plotted.length <= LABEL_ALL_MAX,
              );
              for (const p of [...wanted].sort((a, b) => y(a) - y(b))) {
                // Flip to the left only when the label would run past the plot's right edge.
                const width = short(p.name).length * 12;
                const right = x(p) + 11 + width > W - pad.r;
                const lx = right ? x(p) - 11 : x(p) + 11;
                const x0 = right ? lx - width : lx;
                const x1 = right ? lx : lx + width;
                let ly = y(p) < pad.t + 24 ? y(p) + 20 : y(p) - 9;
                for (
                  let guard = 0;
                  guard < 6 &&
                  boxes.some(
                    (b) => b.x0 < x1 && x0 < b.x1 && Math.abs(b.y - ly) < 15,
                  );
                  guard++
                ) {
                  ly += 16;
                }
                ly = Math.min(ly, H - pad.b - 4);
                boxes.push({ x0, x1, y: ly });
                placedLabels.set(p.productId, {
                  x: lx,
                  y: ly,
                  anchor: right ? "end" : "start",
                });
              }
              return null;
            })()}
            {plotted.map((p, i) => {
              const current = p.productId === currentId;
              const on = active === i;
              return (
                <g
                  key={p.productId}
                  className="cursor-pointer"
                  onMouseEnter={() => setActive(i)}
                  onClick={() => navigate(`/products/${p.productId}`)}
                >
                  {/* a hit target larger than the mark */}
                  <circle
                    cx={x(p)}
                    cy={y(p)}
                    r={14}
                    className="fill-transparent"
                  />
                  <circle
                    cx={x(p)}
                    cy={y(p)}
                    r={current ? 8 : 6}
                    strokeWidth={2}
                    className={
                      current
                        ? "fill-ink stroke-surface"
                        : on
                          ? "fill-ink/60 stroke-surface"
                          : "fill-surface stroke-ink/70"
                    }
                  />
                  {placedLabels.has(p.productId) ? (
                    <text
                      x={placedLabels.get(p.productId)!.x}
                      y={placedLabels.get(p.productId)!.y}
                      textAnchor={placedLabels.get(p.productId)!.anchor}
                      className="fill-ink text-[12px] font-semibold"
                    >
                      {short(p.name)}
                    </text>
                  ) : null}
                </g>
              );
            })}
          </svg>
          {shown ? (
            <div
              role="tooltip"
              className="pointer-events-none absolute right-2 top-2 max-w-[16rem] rounded-lg border border-line bg-surface px-3 py-2 text-xs shadow-card"
            >
              <p className="font-semibold text-ink [overflow-wrap:anywhere]">
                {shown.name}
              </p>
              <p className="text-muted">
                리뷰 {count(shown.reviews)}건 · 칭찬{" "}
                {count(shown.praiseReviews)}건 (
                {percent(shown.praiseReviews, shown.reviews)}) · 불만{" "}
                {count(shown.complaintReviews)}건 (
                {percent(shown.complaintReviews, shown.reviews)})
              </p>
            </div>
          ) : null}
          <p id={liveId} className="sr-only" aria-live="polite">
            {shown
              ? `${shown.name}: 리뷰 ${shown.reviews}건, 칭찬 ${shown.praiseReviews}건, 불만 ${shown.complaintReviews}건`
              : ""}
          </p>
        </div>
      )}
      <ComparisonTable rows={data.products} currentId={currentId} />
      {offPlot > 0 ? (
        <p className="text-xs text-muted">
          그래프에는 리뷰가 많은 상품 {count(PLOT_MAX)}개만 그렸어요. 나머지{" "}
          {count(offPlot)}개는 표에 있어요.
        </p>
      ) : null}
      {thin.length > 0 ? (
        <p className="text-xs text-muted">
          리뷰 {data.minReviews}건 미만이라 그래프에서 뺀 상품{" "}
          {count(thin.length)}개 (표에는 있어요).
        </p>
      ) : null}
      {data.unlinkedReviews + data.unlinkedInquiries > 0 ? (
        <p className="text-xs text-muted">
          상품이 연결되지 않은 리뷰 {count(data.unlinkedReviews)}건 · 문의{" "}
          {count(data.unlinkedInquiries)}건은 비교에 들어가지 않았어요.
        </p>
      ) : null}
      {data.unspecifiedReviews + data.unspecifiedInquiries > 0 ? (
        <p className="text-xs text-muted">
          「(미지정 상품)」으로 들어온 리뷰 {count(data.unspecifiedReviews)}건 ·
          문의 {count(data.unspecifiedInquiries)}건은 상품이 아니라서 비교에서
          뺐어요.
        </p>
      ) : null}
    </div>
  );
}

/** At most this many points on the plot, and this many table rows per step. */
const PLOT_MAX = 20;
/** With this few points every point carries its name; beyond it only this product and the hovered one. */
const LABEL_ALL_MAX = 6;
const TABLE_STEP = 20;

function ComparisonTable({
  rows,
  currentId,
}: {
  rows: ProductPoint[];
  currentId?: string;
}) {
  // Rows are revealed in steps so a large catalogue is not laid out at once. A hidden row is not a zero row:
  // the line under the table says how many are not shown yet.
  const [limit, setLimit] = useState(TABLE_STEP);
  const shownRows = rows.slice(0, limit);
  const hidden = rows.length - shownRows.length;
  return (
    <div>
      {/* Phone: one stacked row per product, so no column sits off-screen behind a horizontal scroll. */}
      <ul className="divide-y divide-line/60 sm:hidden">
        {shownRows.map((p) => (
          <li key={p.productId} className="py-1">
            <Link
              to={`/products/${p.productId}`}
              aria-current={p.productId === currentId ? "page" : undefined}
              className="flex min-h-[44px] items-center rounded text-sm text-ink hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700 [overflow-wrap:anywhere]"
            >
              <span
                className={
                  p.productId === currentId ? "font-semibold" : undefined
                }
              >
                {p.name}
              </span>
              {p.productId === currentId ? (
                <span className="ml-1 text-xs text-muted">이 상품</span>
              ) : null}
            </Link>
            <p className="text-xs tabular-nums text-muted">
              리뷰 {count(p.reviews)} · 칭찬 {count(p.praiseReviews)}
              {p.enough ? ` (${percent(p.praiseReviews, p.reviews)})` : ""} ·
              불만 {count(p.complaintReviews)}
              {p.enough
                ? ` (${percent(p.complaintReviews, p.reviews)})`
                : " · 표본 적음"}{" "}
              · 문의 {count(p.inquiries)} (별도 분모)
            </p>
          </li>
        ))}
      </ul>
      <div className="hidden overflow-x-auto sm:block">
        <table className="w-full min-w-[32rem] text-sm">
          <caption className="sr-only">가게 안 상품 비교 표</caption>
          <thead>
            <tr className="border-b border-line text-left text-xs text-muted">
              <th className="py-1.5 font-normal">상품</th>
              <th className="py-1.5 text-right font-normal">리뷰</th>
              <th className="py-1.5 text-right font-normal">칭찬</th>
              <th className="py-1.5 text-right font-normal">불만</th>
              <th className="py-1.5 text-right font-normal">
                문의 (별도 분모)
              </th>
            </tr>
          </thead>
          <tbody>
            {shownRows.map((p) => (
              <tr key={p.productId} className="border-b border-line/60">
                <td className="max-w-[16rem] py-1.5">
                  <Link
                    to={`/products/${p.productId}`}
                    aria-current={
                      p.productId === currentId ? "page" : undefined
                    }
                    className={`inline-flex min-h-[44px] items-center rounded hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700 [overflow-wrap:anywhere] sm:min-h-0 ${
                      p.productId === currentId
                        ? "font-semibold text-ink"
                        : "text-ink"
                    }`}
                  >
                    {p.name}
                  </Link>
                  {p.productId === currentId ? (
                    <span className="ml-1 text-xs text-muted">이 상품</span>
                  ) : null}
                </td>
                <td className="py-1.5 text-right tabular-nums">
                  {count(p.reviews)}
                </td>
                <td className="py-1.5 text-right tabular-nums">
                  {count(p.praiseReviews)}{" "}
                  <span className="text-xs text-muted">
                    {p.enough
                      ? percent(p.praiseReviews, p.reviews)
                      : "표본 적음"}
                  </span>
                </td>
                <td className="py-1.5 text-right tabular-nums">
                  {count(p.complaintReviews)}{" "}
                  <span className="text-xs text-muted">
                    {p.enough
                      ? percent(p.complaintReviews, p.reviews)
                      : "표본 적음"}
                  </span>
                </td>
                <td className="py-1.5 text-right tabular-nums">
                  {count(p.inquiries)}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <div className="mt-1 flex flex-wrap items-center gap-2 text-xs text-muted">
        <span>
          상품 {count(rows.length)}개 중 {count(shownRows.length)}개 표시
        </span>
        {hidden > 0 ? (
          <button
            type="button"
            onClick={() => setLimit((n) => n + TABLE_STEP)}
            className="inline-flex min-h-[44px] items-center rounded px-2 text-sm text-ink underline underline-offset-2 focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700 sm:min-h-0"
          >
            {count(Math.min(TABLE_STEP, hidden))}개 더 보기 (남은{" "}
            {count(hidden)}개)
          </button>
        ) : null}
      </div>
    </div>
  );
}

function short(name: string): string {
  const plain = name.replace(/^\[(합성|데모)\]\s*/, "");
  return plain.length > 14 ? `${plain.slice(0, 13)}…` : plain;
}
