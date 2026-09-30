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
    tagHidden || !tag || tag === stateWord?.text ? null : (
      <span className={`shrink-0 rounded-md px-1.5 py-px text-xs font-semibold ${TAG[tone]}`}>{tag}</span>
    );
  const lead = stateWord ? (
    <Status tone={stateWord.tone} variant="word">
      {stateWord.text}
    </Status>
  ) : null;

  // <b>The inbox row.</b> Two columns, no tile, no verb. The customer's own sentence is the only thing that is
  // not muted, and its measure is capped so a 1,144px list never stretches one line of Korean across the screen
  // — a row whose text runs the full width of the page is a table cell however it is styled.
  if (dense) {
    const meta = [source, line].filter(Boolean).join(" · ");
    const inner = (
      <>
        <span className="min-w-0 flex-1">
          {/* The state word owns the first line alone, the way `WorkItem` draws a row with a body: with
              the sentence beside it the two competed for the first glance and the state lost, because the
              sentence is longer and darker. A category badge that survived the dedupe rides with it. */}
          {lead || badge ? (
            <span className="flex flex-wrap items-center gap-x-2 gap-y-1">
              {lead}
              {badge}
            </span>
          ) : null}
          <span className="mt-0.5 block min-w-0 max-w-[62ch] break-keep text-sm font-semibold leading-snug text-ink [overflow-wrap:anywhere]">
            {title}
          </span>
          {meta ? <span className="mt-0.5 block truncate text-xs text-muted">{meta}</span> : null}
        </span>
        {rating != null || wait ? (
          <span className="flex shrink-0 flex-col items-end gap-1 pt-px text-xs tabular-nums text-muted">
            {rating != null ? <span aria-label={`별점 ${rating}점`}>★{rating}</span> : null}
            {wait ? <span className="whitespace-nowrap">{wait}</span> : null}
          </span>
        ) : null}
        {action}
      </>
    );
    // The fill is the selection (Intercom draws it exactly this way: a soft rounded fill, no border and no left
    // bar), and the row that carries it drops the hairline above it so the two marks never stack.
    const shape = `flex items-start gap-4 rounded-lg px-3 py-3 transition focus:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-brand-700 ${
      selected ? "!border-transparent bg-brand-50" : "hover:bg-canvas"
    }`;
    return (
      <li className="px-1.5 [&+&>*]:border-t [&+&>*]:border-line">
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
