import type { ReactNode } from "react";
import { Link } from "react-router-dom";
import { Status, type StatusTone } from "./Status";

/**
 * A row of work (docs/reviewnary_design.md §6, §7).
 *
 * The shape of every queue: state word → the sentence the seller recognises the work by → a meta line
 * (channel · product) → time on the right, and optionally one action. The state is the FIRST thing in
 * the row because "what do I do with this" is the question a queue answers; the sentence is the largest
 * because it is what the seller recognises.
 *
 * It renders as a link when `to` is given (the whole row is the target, one tab stop), otherwise as a
 * plain row that can hold its own action.
 *
 * <b>Three text levels when a row has a body, two when it does not.</b> A `title` + `body` row has
 * three things to rank — 무슨 일인지 / 왜 봐야 하는지 / 어디서 온 건지 — and with the meta line above
 * the title the channel name was competing with the state word for the first glance while the subject
 * sat third. So a row WITH a body puts the meta line last, where it is the quietest of the three; a
 * row without one keeps the meta beside the state word, which is the better reading when there are
 * only two levels and is what every existing caller gets, unchanged.
 */
export function WorkItem({
  state,
  tone = "neutral",
  title,
  body,
  meta,
  time,
  to,
  action,
  selected = false,
  dim = false,
  ariaCurrent,
  onClick,
}: {
  state?: string | null;
  tone?: StatusTone;
  title: ReactNode;
  /**
   * The customer's own words under the subject, two lines at most and quieter than the title.
   *
   * <b>Why a row needs both.</b> The 문의 record used to render `snippet` in the TITLE slot, so the
   * inquiry's actual subject never appeared anywhere in the list and the body wore the subject's
   * weight. Pass a body only when it says something the title does not — `inquiryHeadline` is what
   * decides that, and it returns null rather than the same sentence twice.
   */
  body?: ReactNode;
  meta?: ReactNode;
  time?: ReactNode;
  to?: string;
  action?: ReactNode;
  selected?: boolean;
  /** An old or settled row: same information, quieter ink. */
  dim?: boolean;
  ariaCurrent?: "true" | "page";
  /** Side effect as the seller leaves — telemetry or opening the panel, never navigation of its own. */
  onClick?: () => void;
}) {
  const metaLine = meta ? <span className="break-keep text-sm text-muted">{meta}</span> : null;
  const stateWord = state ? <Status tone={tone} variant="word">{state}</Status> : null;
  const layered = body != null;
  const rowBody = (
    <>
      <div className="min-w-0 flex-1">
        {/* With a body, this line is the state word alone — the meta moves below, out of the way of
            the subject. Without one it keeps the channel beside the state, as before. */}
        {stateWord || (!layered && metaLine) ? (
          <div className="flex flex-wrap items-center gap-x-2 gap-y-0.5">
            {stateWord}
            {layered ? null : metaLine}
          </div>
        ) : null}
        <p
          className={`mt-0.5 break-keep text-base font-semibold leading-snug ${
            layered ? "line-clamp-1" : "line-clamp-2"
          } ${dim ? "text-muted" : "text-ink"}`}
        >
          {title}
        </p>
        {layered ? (
          <p className="mt-1 line-clamp-2 break-keep text-sm leading-relaxed text-muted">{body}</p>
        ) : null}
        {layered && metaLine ? <p className="mt-1.5">{metaLine}</p> : null}
      </div>
      {time ? <span className="shrink-0 whitespace-nowrap text-sm tabular-nums text-muted">{time}</span> : null}
      {action ? <div className="shrink-0">{action}</div> : null}
    </>
  );
  const shell = `flex items-start gap-3 px-4 py-3 ${selected ? "bg-brand-50/70" : ""}`;
  if (to) {
    return (
      <Link
        to={to}
        aria-current={ariaCurrent}
        onClick={onClick}
        className={`${shell} transition hover:bg-canvas focus:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-brand-700`}
      >
        {rowBody}
      </Link>
    );
  }
  return <div className={shell}>{rowBody}</div>;
}
