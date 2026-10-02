import { Link } from "react-router-dom";
import type { HomePreparedItem } from "../../lib/types";
import { preparedStateWord } from "../../lib/preparedState";

/**
 * <b>How a piece of already-decided work reads on a Home — in one place, for every Home there is.</b>
 *
 * <p>Each row is something a record already says is ready: a reply the seller approved, a draft that exists, an
 * improvement they accepted. Nothing here is derived from 「이건 답장이 필요해 보인다」 — a Home that invented work
 * would be asking for something no record supports.
 *
 * <p><b>Every row is a link out and nothing else.</b> No button, no verb that sounds like dispatch: this product
 * has no dispatcher, and the seller finishes the work on the surface that owns it — the review's own reply screen,
 * the inquiry, the problem. Rendering a control here that looked like 「보내기」 would promise a send this
 * component cannot perform and no approval covers.
 *
 * <p>The distinguishing fact leads when there is one. Four rows reading 「승인된 리뷰 답변」 are four links a seller
 * cannot choose between; the kind of work follows as the quieter half.
 *
 * <p><b>Each row says what IT is waiting for.</b> The section above used to carry a single badge —
 * 「승인함 · 등록 전」 — which was a claim about a standing approval and was false of every inquiry row on the
 * list. The word now comes from the row's own record (`preparedStateWord`), and a row with nothing distinct
 * to say stays silent rather than repeating the same label down the column.
 */
export function PreparedWorkList({
  rows,
  linkFor,
  selectedId,
  bare = false,
}: {
  rows: readonly HomePreparedItem[];
  /** Where a row points when the page can draw it in place (the 오늘 pane); the row's own screen otherwise. */
  linkFor?: (row: HomePreparedItem) => string;
  selectedId?: string | null;
  /**
   * <b>The caller is already a card</b> (Home visual target, 2026-10-01).
   *
   * <p>On 오늘 this list now stands inside a bordered section that carries the heading, the count and the
   * way out, so its own border and radius drew a second edge 16px inside the first — the 「카드 안의
   * 카드」 §4 spends exactly one boundary on. The rows keep their hairlines, which is what separates
   * them; everything that said 「this is one object」 belongs to the object that now says it.
   */
  bare?: boolean;
}) {
  if (rows.length === 0) return null;
  return (
    <ul className={`divide-y divide-line ${bare ? "mt-2" : "mt-3 overflow-hidden rounded-2xl border border-line bg-surface"}`}>
      {rows.map((row) => {
        const state = preparedStateWord(row);
        return (
        <li key={`${row.kind}-${row.id}`}>
          <Link
            to={linkFor ? linkFor(row) : row.to}
            aria-current={selectedId === row.id ? "true" : undefined}
            className={`block break-keep py-2 text-ink focus:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-brand-700 ${bare ? "" : "px-4"} ${
              selectedId === row.id ? "bg-brand-50 shadow-selected" : "hover:bg-canvas"
            }`}
          >
            {/*
              <b>What it is, then which one</b> (Home visual target, 2026-10-01).

              <p>The row ran label, detail and state along one line, and on 오늘 that line is inside a
              590px card: measured, every row wrapped, and the wrap fell between the product name and the
              word that says what the row IS. The kind leads on its own line with the state right-aligned
              beside it — the two facts a seller chooses between rows on — and the product and date stand
              under them as the one that identifies WHICH. Same three strings, same order of importance.
            */}
            {/*
              <b>One line: what it is, which one, what it waits for</b> (Home visual target, 2026-10-01).

              <p>Measured at 1600×1000 the two-line reading made this card 246px tall inside a brief whose
              whole budget to the composer is 911px, and the card is one of five things that budget has to
              hold. The target draws these rows at ~40px, and all three facts fit on one: the kind leads,
              the product and date identify WHICH (they take the elastic column and truncate, because they
              are the longest and the least load-bearing at their tail), and the state is right-aligned
              where the eye already goes for it on the work rows above.
            */}
            <span className="flex items-baseline gap-2">
              <span className="shrink-0 break-keep font-semibold">{row.label}</span>
              {row.detail ? <span className="min-w-0 flex-1 truncate text-sm text-muted">{row.detail}</span> : <span className="flex-1" />}
              {state ? <span className="shrink-0 text-sm text-muted">{state}</span> : null}
              <span className="shrink-0 text-muted" aria-hidden="true">›</span>
            </span>
          </Link>
        </li>
        );
      })}
    </ul>
  );
}
