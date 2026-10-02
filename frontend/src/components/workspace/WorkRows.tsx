import { DecisionList, DecisionRow } from "../ui/DecisionRow";
import { selectionHref } from "./MasterDetail";
import { isOldBacklog, sharedRowFacts, type HomeWorkRow } from "../../lib/homeWork";
import { elapsedLabel } from "../../lib/copy/customerOps";
import type { CaseQueueState } from "../../pages/app/OperationsCase";

/**
 * The rows of 확인할 일 — on 오늘 (the first few) and on the queue (all of them) — drawn once, here.
 *
 * <p><b>No row carries a button.</b> Every row used to end in 「검토」 — twenty-seven identical controls, the first one
 * solid, on a list whose rows were already links. A row is now a selection: on a wide screen it opens the item in the
 * pane beside the list, where the one primary action lives; on a narrow one it opens the item's own screen, exactly
 * as before.
 *
 * <p><b>A reason every row carries is not a reason to tell them apart</b> (`lib/sharedWord.ts`). Measured on the
 * real org at 1440×900, all five rows of 오늘 read 「리뷰」 in a badge and 「쿠팡 리뷰 ★1」 beside it — the word
 * twice on one line, five times down the list, on a screen whose content is what the customers wrote. When every
 * row shares the tag the rows drop the badge and the list says it once: on 오늘 the heading already does
 * ({@code reasonCounts}, with its count), which is what {@code captionSaysReason} means; anywhere else this list
 * prints the caption itself, so the word is never simply lost.
 *
 * <p><b>The wait leads the row</b>, because this list is ordered by it. It used to sit right-aligned at x=1327 —
 * 990px from the start of the sentence it qualifies — so the sort key was the last thing the eye reached. Nothing
 * else about the row moved and no ordering changed.
 *
 * <p><b>The two groups are drawn, not just sorted.</b> {@link isOldBacklog} has ordered this list inside two groups
 * since the Demo Core freeze; a year-plus backlog row still waited in the same unbroken list, at the same weight as
 * this month's. A divider now says where the old ones start. Nothing is hidden, folded or re-ordered.
 */
export function WorkRows({
  rows,
  selectedKey,
  wide,
  search,
  now,
  ariaLabel,
  showBacklogDivider = true,
  dense = false,
  captionSaysReason = false,
  sharedOver,
  reading = "home",
}: {
  rows: HomeWorkRow[];
  selectedKey: string | null;
  wide: boolean;
  /** The page's current query string, kept on every selection link. */
  search: string;
  now?: Date;
  ariaLabel: string;
  showBacklogDivider?: boolean;
  /** {@link DecisionRow}'s two-line reading. Both lists use it: on the queue scrolling IS the work, and on 오늘
      the three-line reading spent 60% of the viewport on five rows and pushed the list's own exit under the dock. */
  dense?: boolean;
  /** The screen's own heading already names what every row shares (오늘 prints it beside the count). Then this
      list does not print a second caption for it. */
  captionSaysReason?: boolean;
  /**
   * The population the caller's caption speaks for. A brief draws five rows of a list of eleven, and a fact may
   * only be lifted off the rows when it is true of every row the caption covers — not merely of the five drawn.
   * Defaults to the rows drawn, which is the whole list wherever there is no brief.
   */
  sharedOver?: HomeWorkRow[];
  /**
   * <b>Which of the two dense readings this list draws</b> (확인할 일 canonical mockup, 2026-10-02).
   *
   * <p>「home」 is the reading the Home's baseline was frozen on (product-owner decision, 2026-10-01):
   * provenance right-aligned opposite the sentence. 「queue」 is the canonical mockup's: provenance on
   * its own line under the sentence, with a review's product moved there off the second line, and only
   * the time on the right.
   *
   * <p>The facts are identical in both — this chooses columns, never content — and the default is the
   * frozen one, so a caller that does not ask gets the Home.
   */
  reading?: "home" | "queue";
}) {
  const caseIds = rows.map((r) => r.caseId).filter((id): id is string => id !== null);
  const shared = sharedRowFacts(sharedOver ?? rows);
  const firstOld = showBacklogDivider ? rows.findIndex((r) => isOldBacklog(r, now ?? new Date())) : -1;
  const oldCount = firstOld >= 0 ? rows.length - firstOld : 0;

  const queue = reading === "queue";
  const draw = (row: HomeWorkRow) => {
    // A review opened on its own page from here offers the way back to here.
    const fullScreen = row.kind === "REVIEW" ? `${row.to}?from=work` : row.to;
    /* <b>A review's second line IS its product</b> (`mergeHomeWork`), and the queue reading draws the
       product on the provenance line. So for a review the second line is handed over rather than
       duplicated; for an inquiry and a case the second line is the customer's own words and stays
       where it is. Nothing is dropped and no field is re-derived — this moves one string between two
       slots of the same row. */
    const product = queue && row.kind === "REVIEW" ? row.line : null;
    /* <b>The queue leads with the customer's sentence</b> (product-owner decision, 2026-10-02). An
       inquiry's `title` is the SUBJECT line the customer typed, and 「문의 드립니다」 is what nearly all
       of them type — so the queue's first two rows were the same four characters and told the seller
       nothing. {@link HomeWorkRow.said} is the body, off the wire, absent when the record has none.

       <p>Only the queue. Home is frozen on `title` (2026-10-01) and asks for neither this nor
       `metaBelow`, so the row it gets is the row it had. */
    const lead = queue && row.said ? row.said : row.title;
    /* <b>The queue row carries no judgement of ours</b> (product-owner decision, 2026-10-02).
       {@link HomeWorkRow.line} is three different things depending on where the row came from: a
       CASE's is the case `summary` — 「등록된 지식으로 답변할 수 있는 문의입니다…」, which is
       reviewnary's own reading and its ranking of this item against the others — while an INQUIRY's
       is the customer's own snippet and a REVIEW's is the product. The first is the only one the
       seller did not write and cannot check from the row, and 왜 지금 볼 일인가 in the detail owns it
       outright; printing it here made a 46-row list argue its case 46 times.

       <p>So the kind decides, not the string: customer and product context stay, our account goes.
       The prefix test still applies to what remains, because a snippet can still be its own title. */
    const ours = queue && row.kind === "CASE";
    const second = !ours && product === null && row.line && !echoes(row.line, lead) ? row.line : null;
    return (
      <DecisionRow
        key={row.key}
        tone={row.reason.tone}
        icon={row.reason.icon}
        tag={row.reason.tag}
        work={row.state}
        title={lead}
        line={second}
        /* <b>One contract, and the pane applies the same one</b> (elapsed-time contract, 2026-10-02).
           The branch was on `kind` — which record carries the row — so a CASE opened about a review
           said 「N일 대기」 about a review nobody was waiting on. {@link elapsedLabel} reads `subject`,
           which is what the item is ABOUT, and it is the only place the word is chosen. */
        wait={elapsedLabel(row.since, row.subject, now)}
        rating={shared.rating === null ? row.rating : null}
        source={shared.source === null ? row.source : null}
        tagHidden={shared.tag !== null}
        to={selectionHref(wide, row.key, fullScreen, search)}
        state={!wide && row.caseId ? ({ caseIds } satisfies CaseQueueState) : undefined}
        selected={wide && row.key === selectedKey}
        dense={dense}
        metaBelow={queue}
        product={product}
      />
    );
  };

  const said = sharedPhrase(shared);
  const caption = said && !captionSaysReason ? <p className="mb-2 px-1 text-sm text-muted">{said}</p> : null;

  if (firstOld <= 0) {
    return (
      <>
        {caption}
        <DecisionList ariaLabel={ariaLabel} plain={dense}>
          {rows.map(draw)}
        </DecisionList>
      </>
    );
  }
  return (
    <div className="space-y-4">
      {caption}
      <DecisionList ariaLabel={ariaLabel} plain={dense}>
        {rows.slice(0, firstOld).map(draw)}
      </DecisionList>
      <p className="flex items-center gap-3 text-sm font-semibold text-muted" role="separator">
        <span>1년 넘게 기다린 것 {oldCount.toLocaleString("ko-KR")}건</span>
        <span aria-hidden="true" className="h-px flex-1 bg-line" />
      </p>
      <DecisionList ariaLabel={`${ariaLabel} · 1년 넘게 기다린 것`} plain={dense}>
        {rows.slice(firstOld).map(draw)}
      </DecisionList>
    </div>
  );
}

/** True when the second line would only repeat the sentence already drawn above it. */
function echoes(line: string, lead: string): boolean {
  const a = line.trim();
  const b = lead.trim().replace(/…$/, "");
  return a === b || a.startsWith(b) || b.startsWith(a);
}

/**
 * 「모두 쿠팡 ★1 리뷰」 — what the whole list says, said once. Null when the rows differ, which is the ordinary
 * case and the one where every fact belongs on its own row.
 *
 * <p><b>「모두」 stays</b> (product-owner decision, 2026-09-26). The phrase sits where Intercom draws 「5 Open ⌄」
 * and Linear draws its filter pills — the position of a filter — and this list is not filtered. Dropping the word
 * would turn a true claim about all eleven rows into a label that invites 「so what is hidden?」. What was actually
 * awkward is that 「쿠팡 리뷰」 and 「★1」 arrived as two glued tokens; composed from the three hoisted pieces it is
 * one noun phrase, and the composition never takes {@link HomeWorkRow.source} apart.
 */
export function sharedPhrase(shared: {
  tag: string | null;
  source: string | null;
  rating: number | null;
  channel: string | null;
  subject: string | null;
}): string | null {
  const star = shared.rating === null ? null : `★${shared.rating}`;
  // Channel and noun were hoisted separately, so the star can stand between them.
  if (shared.channel && shared.subject) {
    return `모두 ${[shared.channel, star, shared.subject].filter(Boolean).join(" ")}`;
  }
  // No channel name to place the star against: the composed source (or the reason tag) is all there is, and it
  // already reads as one phrase. The source already contains the noun the tag repeats (「쿠팡 리뷰」 vs 「리뷰」).
  const parts = [shared.source ?? shared.tag, star].filter(Boolean);
  return parts.length > 0 ? `모두 ${parts.join(" ")}` : null;
}
