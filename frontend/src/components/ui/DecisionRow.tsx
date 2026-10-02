import type { ReactNode } from "react";
import { Link } from "react-router-dom";
import type { ReasonIcon, ReasonTone } from "../../lib/copy/customerOps";
import { WORK_STATE, type WorkStateKey } from "../../lib/workState";
import { Status } from "./Status";

/**
 * One thing waiting for the seller: why (tag + icon tile), where from, what, the one line Reviewnary adds, how long
 * it has waited, and the verb.
 *
 * <b>One interactive element per row.</b> The whole row is the link; the verb at its end is drawn as a button but is
 * not a second control — two links to the same place would be read out twice and tabbed through twice.
 * A row with `action` instead of `to` (the 지식 inbox, whose editor opens in place) renders its controls there.
 */
const TILE: Record<ReasonTone, string> = {
  amber: "bg-warn/10 text-warn",
  blue: "bg-brand-50 text-brand-700",
  gray: "bg-canvas text-muted",
};

/**
 * <b>A category badge may never carry a work-state word</b> (canonical mockup semantic correction,
 * 2026-10-02).
 *
 * <p>{@code REASON} holds real categories (교환·환불 · 정보 부족 · 리뷰 · 판단 보류) and three entries that
 * are states wearing a category badge (답변 필요 · 승인 대기 · 초안 필요). The row already dropped the badge
 * when it was the SAME word as the lead column's, and that was not the rule — it was one case of it.
 * Measured at 1600×1000 on the live org, a case whose answer reviewnary had already written drew
 * 「초안 준비됨」 in the lead column and 「답변 필요」 in its provenance line: one row, two states, and the
 * one that was false was the one telling the seller to start work that was done.
 *
 * <p>So the test is the table, not the neighbour: a word that belongs to {@link WORK_STATE} is owned by
 * the lead column wherever it appears, and this slot draws only what is genuinely a category.
 */
const STATE_WORDS: ReadonlySet<string> = new Set(Object.values(WORK_STATE).map((w) => w.text));

const TAG: Record<ReasonTone, string> = {
  amber: "bg-warn/10 text-warn",
  blue: "bg-brand-50 text-brand-700",
  gray: "bg-canvas text-muted",
};

export function DecisionRow({
  tone,
  icon,
  tag,
  work,
  source,
  title,
  line,
  wait,
  verb,
  primary = false,
  to,
  state,
  action,
  children,
  selected = false,
  dense = false,
  rating = null,
  tagHidden = false,
  metaBelow = false,
  product = null,
}: {
  tone: ReasonTone;
  icon: ReasonIcon;
  tag: string;
  /**
   * <b>「내가 뭘 해야 하나」, first</b> (product-owner decision, 2026-09-30).
   *
   * <p>The leading slot of an operating queue is the state word — 답변 필요 / 확인 필요 / 초안 준비됨 /
   * 승인 대기 — and {@link tag} is secondary. `WorkItem`, which every other queue in this product uses,
   * has always put the state first for the reason §6 gives: the question a queue answers is what to do
   * with each item. This row led with a category badge instead, so 확인할 일 named what each item was.
   *
   * <p>Optional because the 지식 받은함 is not an operating queue — its rows are things to fill in, not
   * work in a lifecycle — and a state word invented for them would be a word naming no fact.
   */
  work?: WorkStateKey;
  source?: string | null;
  title: string;
  line?: string | null;
  wait?: string | null;
  verb?: string;
  primary?: boolean;
  to?: string;
  state?: unknown;
  /** Controls drawn in place of a link (a row whose work happens on this screen). */
  action?: ReactNode;
  /** Opened below the row — an inline editor. */
  children?: ReactNode;
  /**
   * The row whose detail stands in the master-detail pane. A selectable list passes no `verb`: the row IS the
   * control, and the one primary action lives in the detail (UI/UX v2 Phase 1).
   */
  selected?: boolean;
  /**
   * <b>The inbox reading: two columns and two lines</b> (reference-based hierarchy v1).
   *
   * <p>Studied against Linear's Triage list and Intercom's Inbox at 1440×900. Both draw a work row as
   * <b>content on the left, metadata on the right, and nothing in between</b> — Linear: 「제목 … ENG-619」 over
   * 「◻ Intercom … 6m ago」; Intercom: 「이름」 over 「미리보기 … 2m」. Neither has a badge, a tile, a button or a
   * third column, and in both the age is right-aligned, small and muted.
   *
   * <p>This reading had all three of those. A leading wait column was added here a round earlier on the argument
   * that a list ordered by age should lead with it; put side by side with the references that column is the
   * thing that makes the list read as a table, and the heading already says 「오래된 순」. Retired.
   *
   * <p><b>Nothing is dropped</b> — the same five facts, re-columned: title, then `source · line` under it, and
   * the rating over the wait on the right. Rows that open an editor in place (the 지식 받은함) keep the
   * three-line reading, where the extra air is the point.
   */
  dense?: boolean;
  /** The star rating, drawn above the wait in the right-hand column. Carried by the row; never parsed out of
      `source`, which is why `sourceLabel` stops composing it for these lists. Null when every row in the list
      carries the same one and the list has said it once (`sharedRowFacts`). */
  rating?: number | null;
  /**
   * <b>Every row in this list carries the same tag</b>, so it distinguishes nothing and the list says it once in
   * its own caption instead (`lib/sharedWord.ts`; the list owns that judgement, this row only obeys it).
   *
   * <p><b>The tile goes with it</b>, in the dense reading. Tone and icon are functions of the reason, so when the
   * reason is shared they are five identical coloured squares down a list whose content is what the customers
   * wrote — the same repeated mark the rule exists to remove, drawn instead of spelled. Where the reasons differ
   * the tile is a distinction and stays. The three-line reading keeps it either way: there the tile is the row's
   * left anchor and nothing else occupies that column.
   */
  tagHidden?: boolean;
  /**
   * <b>Provenance under the sentence instead of beside it</b> (확인할 일 canonical mockup, 2026-10-02).
   *
   * <p>The dense row's default reading right-aligns 「category · source · ★」 opposite the title, which is
   * what the Home draws and what the Home's baseline froze on 2026-10-01. The queue's canonical mockup
   * reads differently: three ranks stacked in one column — what the customer said, then the line
   * reviewnary adds, then where it came from — with only the time on the right. Same facts, same row, a
   * different column assignment; a prop rather than a second component, so neither list can drift from
   * the other on anything but this.
   *
   * <p>Off by default, so the Home's frozen reading is the one a caller gets without asking.
   */
  metaBelow?: boolean;
  /**
   * The product the row is about, drawn last on the provenance line. Only read when {@link metaBelow}: in
   * the default reading a review's product is the row's {@link line}, and moving it would change the Home.
   */
  product?: string | null;
}) {
  const stateWord = work ? WORK_STATE[work] : null;
  /**
   * <b>The badge goes when it would repeat the state word.</b> `REASON` carries real categories
   * (교환·환불 · 정보 부족 · 리뷰 · 판단 보류) and three entries that are states wearing a category badge
   * (답변 필요 · 승인 대기 · 초안 필요). With the state word leading the row, those three would print the
   * same word twice on one line — the defect this package exists to remove — so the badge is drawn only
   * when it says something the state word does not.
   */
  const badge =
    tagHidden || !tag || STATE_WORDS.has(tag) ? null : (
      <span className={`shrink-0 rounded-md px-1.5 py-px text-xs font-semibold ${TAG[tone]}`}>{tag}</span>
    );
  const lead = stateWord ? (
    <Status tone={stateWord.tone} variant="word">
      {stateWord.text}
    </Status>
  ) : null;
  /**
   * <b>In the inbox reading the state is a tinted badge, not a dotted word</b> (Home visual target,
   * 2026-10-01). A dot plus a coloured word is read as a sentence fragment, and it was sharing its line
   * with a category at the same size; alone in a fixed lead column it has to be read as a mark, which is
   * what a tinted box is. Same five words, same five tones, same table ({@link WORK_STATE}) — only the
   * shape of the carrier changes, and `Status` is still the only thing that colours a state.
   *
   * <p>`rounded-lg px-2 py-1` rather than the chip's stadium: §4 gives controls and marks an edge at 8px,
   * and a capsule at this size reads as something pressable.
   */
  const leadBadge = stateWord ? (
    <Status tone={stateWord.tone} variant="badge">
      {stateWord.text}
    </Status>
  ) : null;

  // <b>The inbox row.</b> Two columns, no tile, no verb. The customer's own sentence is the only thing that is
  // not muted, and its measure is capped so a 1,144px list never stretches one line of Korean across the screen
  // — a row whose text runs the full width of the page is a table cell however it is styled.
  if (dense) {
    /**
     * <b>Three columns: what to do, what was said, where it came from</b> (Home visual target, 2026-10-01).
     *
     * <p>The reading before this one stacked all four facts in the left column — state over title over
     * 「source · line」 — and right-aligned the star above the wait. Measured at 1600×1000 that put the
     * channel, the category and the product name in the SAME column as the customer's sentence and at the
     * same left edge, so the eye had to re-read each row to find where one fact ended and the next began.
     *
     * <p>The target splits them: a fixed lead column the state badge is the only occupant of, the content
     * column (sentence, then the line reviewnary adds), and the provenance right-aligned. Nothing is
     * dropped and nothing is derived — the same five facts, re-columned.
     */
    const star = rating != null ? `★${rating}` : null;
    /**
     * <b>The category joins the provenance, and goes when the provenance already says it.</b>
     *
     * <p>It cannot stand beside the state any more — the lead column holds one badge — and the right-hand
     * group is where it belongs: 「리뷰」 is what this row IS, which is the same axis as 「네이버」. The
     * containment check is the rule `sharedPhrase` already states in words: `source` is composed as
     * 「쿠팡 리뷰」, so a 「리뷰」 badge beside it prints the noun twice. This reads our own composed string
     * rather than splitting it, which is the parse this file refuses everywhere else.
     */
    const category = tagHidden || !tag || STATE_WORDS.has(tag) || (source ?? "").includes(tag) ? null : tag;
    const meta = [category, source, star].filter(Boolean).join(" · ");
    /**
     * The provenance line: where it came from, what kind it is, and what it is about — the facts that
     * tell one row from the next once the state and the sentence have been read. Each piece is drawn
     * only when the row carries it, so a row with no rating and no product draws the channel alone
     * rather than a line of separators (canonical mockup, 2026-10-02).
     */
    const hasProvenance = metaBelow && [stateWord, source, category, star, product].some(Boolean);
    const inner = (
      <>
        {/* The one column whose width is fixed. A state badge that starts where the previous row's badge
            started is read as a column; one that starts after a variable-width sibling is read as a word.
            <b>Only the Home's reading.</b> The queue's target demotes the state to the provenance line
            below the sentence (2026-10-02): a 104px gutter of tinted marks is the first thing the eye
            lands on down a list of 46, and what the seller came to read is the customers' sentences. */}
        {leadBadge && !metaBelow ? <span className="w-[104px] shrink-0">{leadBadge}</span> : null}
        <span className="min-w-0 flex-1">
          <span
            className={`block min-w-0 max-w-[62ch] break-keep font-semibold leading-snug text-ink [overflow-wrap:anywhere] ${
              metaBelow ? "text-lg" : "text-sm"
            }`}
          >
            {title}
          </span>
          {/* What reviewnary adds about this item. `xs`, which is the size the provenance beside it takes:
              the row has three ranks — the customer's sentence, then everything that qualifies it — and
              giving this line `sm` made it the same size as the title it sits under while the metadata
              2px to its right was smaller than both. Measured, it is also the 6px per row that lands the
              four of them on the target's 70px pitch. */}
          {line ? (
            <span className={`block truncate text-muted ${metaBelow ? "mt-1 text-sm" : "mt-0.5 text-xs"}`}>{line}</span>
          ) : null}
          {hasProvenance ? (
            /* <b>One line, truncated — never wrapped</b> (measured at 1600×1000 with the 576px pane,
                2026-10-02). Wrapping put the separator at the START of the second line, with the product
                name under it: a divider divides two things and there was nothing to its left. The facts
                are ordered most-identifying-last, so the product is the one that gives way. */
            <span className="mt-1.5 flex min-w-0 flex-nowrap items-center gap-x-2 overflow-hidden text-xs text-muted">
              {/* <b>No channel mark</b> (measured at 1600×1000, 2026-10-02). The canonical mockup opens this
                  line with a lettered tile — C for Cafe24, N for NAVER — and the product's channel names are
                  Korean, so the same tile rendered 「카」 and 「네」: one syllable of a word whose next two
                  syllables stand 6px to its right, which is a truncation rather than a mark. Latin initials
                  would have to be invented here (쿠팡 and 카페24 both start C) and the channels' own brand
                  colours are not in §5's five tones. The name itself is the mark. */}
              {/* <b>The state, demoted but first.</b> It is still the word that decides whether this row is
                  work for the seller or work already done, so it opens the line that qualifies the
                  sentence — and `Status` is still the only thing that colours it. */}
              {stateWord ? (
                <Status tone={stateWord.tone} variant="quiet">
                  {stateWord.text}
                </Status>
              ) : null}
              {source ? (
                stateWord ? (
                  <Fact fixed>{source}</Fact>
                ) : (
                  <span className="shrink-0 whitespace-nowrap">{source}</span>
                )
              ) : null}
              {/* <b>Only the product gives way.</b> A truncated 「★3」 is 「★.」 — a mark with its own value
                  cut off, which is worse than not drawing it; same for a category word. They hold their
                  width and the product name, which is the longest and the one a prefix still identifies,
                  takes the ellipsis. */}
              {category ? <Fact fixed>{category}</Fact> : null}
              {star ? (
                <Fact fixed>
                  <span aria-label={`별점 ${rating}점`}>{star}</span>
                </Fact>
              ) : null}
              {product ? <Fact>{product}</Fact> : null}
            </span>
          ) : null}
        </span>
        {(metaBelow ? null : meta) || wait ? (
          <span className={`flex shrink-0 items-start gap-6 text-muted ${metaBelow ? "pt-1 text-sm" : "pt-px text-xs"}`}>
            {meta && !metaBelow ? (
              <span className="whitespace-nowrap" aria-label={star ? `${meta.replace(star, `별점 ${rating}점`)}` : undefined}>
                {meta}
              </span>
            ) : null}
            {/* Fixed width and right-aligned, so the sort key this list is ordered by lines up down the
                column instead of floating at the end of a variable string. */}
            {wait ? <span className="w-[88px] shrink-0 whitespace-nowrap text-right tabular-nums">{wait}</span> : null}
          </span>
        ) : null}
        {/* <b>No chevron in the queue reading</b> (2026-10-02). Forty-six identical ›, one per row, on a
            list where the row IS the selection and the pane beside it already shows what opening does.
            It is the mark that makes a work list read as an inbox. The Home's reading keeps it: there the
            rows are a brief and they genuinely lead somewhere else. */}
        {to && !metaBelow ? (
          <span aria-hidden="true" className="shrink-0 self-start pt-px text-muted">
            ›
          </span>
        ) : null}
        {action}
      </>
    );
    /**
     * <b>Subtle fill AND a left accent</b> (product-owner decision, 2026-10-01). The fill alone was what
     * Intercom draws, and on their Inbox it is enough because the row and the pane touch. Here they are
     * 440px apart across a hairline, and a soft tint on a white list did not read as 「this row is what the
     * pane is showing」 — a seller had to compare the text to be sure. The accent is the same mark the
     * three-line reading has always used (`shadow-selected`, one token, the brand at 3px), so the two
     * readings of one component now say selection the same way.
     *
     * <p>The row that carries it drops the hairline above it so the two marks never stack.
     */
    /* <b>The queue's row is a band, the Home's is a card</b> (2026-10-02). A rounded tinted rectangle
       inset from both edges is an object sitting ON the list; at 46 rows the target reads as one
       continuous sheet with hairlines, so the selected row runs edge to edge and the accent bar is the
       only thing that marks it. §4's 8px edge belongs to the Home's reading, where the row is one of
       five on a page of cards. */
    const shape = `flex items-start gap-3 transition focus:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-brand-700 ${
      metaBelow ? "px-4 py-4" : "rounded-lg px-3 py-3"
    } ${selected ? "!border-transparent bg-brand-50 shadow-selected" : "hover:bg-canvas"}`;
    return (
      <li className={`${metaBelow ? "" : "px-1.5"} [&+&>*]:border-t [&+&>*]:border-line`}>
        {to ? (
          <Link to={to} state={state} aria-current={selected ? "true" : undefined} className={`group ${shape}`}>
            {inner}
          </Link>
        ) : (
          <div className={shape}>{inner}</div>
        )}
        {children ? <div className="px-3 pb-3">{children}</div> : null}
      </li>
    );
  }

  const tile = (
    <span aria-hidden="true" className={`flex h-10 w-10 shrink-0 items-center justify-center rounded-xl ${TILE[tone]}`}>
      <Icon name={icon} />
    </span>
  );
  const body = (
    <>
      {tile}
      <span className="min-w-0 flex-1">
        <span className="flex flex-wrap items-center gap-x-2 gap-y-1 text-sm text-muted">
          {lead}
          {badge}
          {source ? <span>{source}</span> : null}
        </span>
        <span className="mt-1 block break-keep text-base font-bold leading-snug tracking-tight text-ink [overflow-wrap:anywhere]">
          {title}
        </span>
        {line ? <span className="mt-0.5 block truncate text-sm text-muted">{line}</span> : null}
      </span>
      <span className="flex shrink-0 flex-col items-end gap-2 self-center">
        {wait ? <span className="whitespace-nowrap text-sm tabular-nums text-muted">{wait}</span> : null}
        {verb && to ? (
          <span
            className={`inline-flex min-h-[36px] items-center rounded-lg px-3 text-sm font-semibold ${
              primary ? "bg-brand-700 text-white group-hover:bg-brand-800" : "border border-line bg-surface text-ink group-hover:bg-canvas"
            }`}
          >
            {verb}
          </span>
        ) : null}
        {action}
      </span>
    </>
  );

  return (
    <li className="[&+&]:border-t [&+&]:border-line">
      {to ? (
        <Link
          to={to}
          state={state}
          aria-current={selected ? "true" : undefined}
          className={`group flex items-start gap-3 px-4 py-4 transition focus:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-brand-700 ${
            selected ? "bg-brand-50 shadow-selected" : "hover:bg-canvas"
          }`}
        >
          {body}
        </Link>
      ) : (
        <div className="flex items-start gap-3 px-4 py-4">{body}</div>
      )}
      {children ? <div className="px-4 pb-4 sm:pl-[68px]">{children}</div> : null}
    </li>
  );
}

/**
 * The container of a list of {@link DecisionRow}s.
 *
 * <p><b>`plain` has no container at all</b>, which is what both references do: neither Linear's Triage list nor
 * Intercom's Inbox draws a box around the rows — the rows sit on the page and a hairline separates them. A card
 * around a list adds a second boundary to a thing that already has row boundaries, and at 1,144px wide it is the
 * single strongest reason a work list reads as a table. The bordered reading stays for the lists whose rows open
 * an editor in place, where the box is what says 「this is one object」.
 */
/**
 * One fact on the provenance line, with the hairline that separates it from the one before.
 *
 * <p>{@code fixed} keeps a fact at its own width — for the ones a truncation would falsify (a rating, a
 * category). Everything else shares what is left and truncates.
 */
function Fact({ children, fixed = false }: { children: ReactNode; fixed?: boolean }) {
  return (
    <span className={`flex flex-nowrap items-center gap-2 ${fixed ? "shrink-0" : "min-w-0"}`}>
      <span aria-hidden="true" className="h-3 w-px shrink-0 bg-line" />
      <span className={fixed ? "whitespace-nowrap" : "truncate"}>{children}</span>
    </span>
  );
}

export function DecisionList({ children, ariaLabel, plain = false }: { children: ReactNode; ariaLabel?: string; plain?: boolean }) {
  return (
    <ul
      aria-label={ariaLabel}
      className={plain ? "-mx-1.5" : "overflow-hidden rounded-2xl border border-line bg-surface"}
    >
      {children}
    </ul>
  );
}

function Icon({ name }: { name: ReasonIcon }) {
  const common = { fill: "none", stroke: "currentColor", strokeWidth: 1.8, strokeLinecap: "round" as const, strokeLinejoin: "round" as const };
  return (
    <svg viewBox="0 0 24 24" className="h-[19px] w-[19px]">
      {name === "box" ? <path {...common} d="M4 7h16v12H4z M4 7l2-3h12l2 3 M9 12h6" /> : null}
      {name === "question" ? (
        <>
          <circle {...common} cx="12" cy="12" r="9" />
          <path {...common} d="M9.5 9.5a2.5 2.5 0 114 2c-1 .6-1.5 1.1-1.5 2.2 M12 17h.01" />
        </>
      ) : null}
      {name === "star" ? <path {...common} d="M12 4l2.4 5 5.6.7-4 3.8 1 5.5-5-2.7-5 2.7 1-5.5-4-3.8 5.6-.7z" /> : null}
      {name === "chat" ? <path {...common} d="M4 5h16v11H8l-4 4z" /> : null}
      {name === "scale" ? <path {...common} d="M12 4v16 M6 20h12 M5 8h14 M5 8l-2.5 6a3 3 0 005 0z M19 8l-2.5 6a3 3 0 005 0z" /> : null}
    </svg>
  );
}
