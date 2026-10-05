import { Link } from "react-router-dom";
import { count } from "../../../lib/format";
import { productChannelLabel, type ProductRowFacts } from "../../../lib/productRows";

/**
 * 상품 목록 — the record page's index, drawn as one aligned column per relation.
 *
 * <p><b>Why a table and not the facet sentence it replaces.</b> The row used to print its own labels
 * — 「네이버 · 문의 10 · 리뷰 1,761 · 답변 기준 3 · 최근 문제 근거 42」 — so every value landed at a
 * different x and the catalogue could not be compared down the page. Worse, the facet SET changed per
 * row: a product with no issue evidence dropped that facet entirely, and a product with neither
 * inquiries nor reviews collapsed into a third sentence shape. The labels belong in the heading, once,
 * and the values in a column under them. Attio's table view is the reference, as its record page was
 * the reference for 상품 상세 — the point of taking both from the same product is that the list reads
 * as the index of the page it opens.
 *
 * <p><b>The columns are the record page's relations, named the way that page names them.</b> 답변 대기
 * · 문제 근거 · 문의 · 리뷰 · 상품 지식. The last one used to be called 「답변 기준」 here and 「상품
 * 지식」 there, which is one object under two names across two screens. The emphasis rule travels with
 * them: 답변 대기 is the only count that is a current obligation, so it is the only one raised, exactly
 * as the detail's relation strip raises it.
 *
 * <p><b>문제 근거 rather than 반복 문제, because that is the column the rows are ordered by.</b> Both are
 * on the record page's strip and both come from the same read, but `orderProductRows` breaks its ties
 * on evidence, not on the number of issue objects. A column named for one quantity above rows ranked by
 * another would make the ordering unreadable in the one place it is finally visible — and changing what
 * the list sorts by to suit a heading would be re-ranking the catalogue to fit a label.
 *
 * <p><b>Identity is not volume.</b> 채널 and 상품코드 are single-value static facts — the detail keeps
 * them in the header and the rail — so they sit under the name, not in the counted columns. A product
 * with two or more listings says so and stops: naming one of two listings is the claim the record page
 * refuses to make, and this screen must not make it either.
 *
 * <p><b>0 leaves the cell empty; 「—」 means the read failed.</b> Eighteen of the demo org's twenty head
 * rows hold a zero in most columns, and a field of grey zeros buries the six products that carry
 * something. An empty cell is the table convention for "no value" and here the two coincide. The dash
 * is kept for the one thing a blank must never be confused with — a count this screen could not read —
 * so an unread value still never prints 0.
 *
 * <p>The whole row is the control, as it was before: a link-coloured word repeated down the right edge
 * would be a second copy of the action the row already is.
 */

/** One counted column. The heading and the cells read this, so the two cannot drift apart. */
export const PRODUCT_COLUMNS = [
  { key: "unanswered", label: "답변 대기", emphasis: true },
  { key: "issueEvidence", label: "문제 근거", emphasis: false },
  { key: "inquiries", label: "문의", emphasis: false },
  { key: "reviews", label: "리뷰", emphasis: false },
  { key: "knowledge", label: "상품 지식", emphasis: false },
] as const;

/** What the row needs beyond its facts. `facts` is undefined while unread, null when the read failed. */
export interface ProductTableRow {
  id: string;
  name: React.ReactNode;
  sku: string | null;
  facts: ProductRowFacts | null | undefined;
}

const CELL = "shrink-0 w-[84px] text-right tabular-nums";

export function ProductTableHead() {
  return (
    // aria-hidden: each cell below carries its own label for a screen reader, which reads a row as one
    // object rather than asking the listener to hold five column positions in their head.
    <div aria-hidden="true" className="flex items-center gap-4 border-b border-line bg-canvas/40 px-5 py-2.5 text-xs text-muted">
      <span className="min-w-0 flex-1">상품</span>
      {PRODUCT_COLUMNS.map((column) => (
        <span key={column.key} className={CELL}>
          {column.label}
        </span>
      ))}
    </div>
  );
}

export function ProductTableRowItem({ row }: { row: ProductTableRow }) {
  const f = row.facts;
  return (
    <Link
      to={`/products/${row.id}`}
      className="flex items-center gap-4 px-5 py-2.5 transition hover:bg-canvas focus:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-brand-700"
    >
      <span className="min-w-0 flex-1">
        <span className="block truncate text-sm font-medium text-ink">{row.name}</span>
        <span className="block truncate text-xs text-muted">
          {f ? `${channelText(f.channels)} · ` : null}
          <span className="tabular-nums">{row.sku ?? "상품코드 없음"}</span>
        </span>
      </span>
      {f ? (
        PRODUCT_COLUMNS.map((column) => (
          <Count key={column.key} label={column.label} value={f[column.key]} emphasis={column.emphasis} />
        ))
      ) : (
        <span className="shrink-0 text-xs text-muted">
          {f === undefined ? "확인하는 중…" : "운영 정보를 읽지 못했습니다"}
        </span>
      )}
    </Link>
  );
}

/**
 * One counted cell. Zero is a fact and leaves the cell blank so the marks on the page are the products
 * that carry something; an unread count is a dash and is never drawn as 0.
 */
function Count({ label, value, emphasis }: { label: string; value: number | null; emphasis: boolean }) {
  if (value === null || value === undefined) {
    return (
      <span className={CELL}>
        <span className="sr-only">{label} 읽지 못했습니다</span>
        <span aria-hidden="true" className="text-sm text-muted">
          —
        </span>
      </span>
    );
  }
  if (value === 0) {
    return (
      <span className={CELL}>
        <span className="sr-only">{label} 0건</span>
      </span>
    );
  }
  return (
    <span className={CELL}>
      <span className="sr-only">{label} </span>
      <span className={`text-sm ${emphasis ? "font-bold text-warn" : "font-medium text-ink"}`}>{count(value)}</span>
    </span>
  );
}

/** One listing names its channel; two or more say how many and stop. */
function channelText(channels: readonly string[]): string {
  if (channels.length === 0) return "채널 없음";
  if (channels.length === 1) return productChannelLabel(channels[0]);
  return `${channels.length}개 채널`;
}
